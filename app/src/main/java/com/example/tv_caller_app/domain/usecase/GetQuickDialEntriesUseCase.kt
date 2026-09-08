package com.example.tv_caller_app.domain.usecase

import android.util.Log
import com.example.tv_caller_app.data.repository.CallHistoryRepository
import com.example.tv_caller_app.data.repository.ContactRepository
import com.example.tv_caller_app.data.repository.PresenceRepository
import com.example.tv_caller_app.domain.ContactInfo
import com.example.tv_caller_app.domain.QuickDialEntry

class GetQuickDialEntriesUseCase(
    private val contactRepository: ContactRepository,
    private val callHistoryRepository: CallHistoryRepository,
    private val presenceRepository: PresenceRepository
) {

    companion object {
        private const val TAG = "GetQuickDialEntriesUseCase"
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L
        const val MAX_QUICK_DIAL_SLOTS = 4
    }

    private var cachedEntries: List<QuickDialEntry>? = null
    private var cacheTimestamp: Long = 0

    private fun isCacheValid() =
        cachedEntries != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_DURATION_MS

    fun invalidateCache() {
        Log.d(TAG, "Cache invalidated")
        cachedEntries = null
        cacheTimestamp = 0
    }

    suspend operator fun invoke(forceRefresh: Boolean = false): List<QuickDialEntry> {
        if (!forceRefresh && isCacheValid()) {
            Log.d(TAG, "Returning cached quick dial entries (${cachedEntries!!.size} entries)")
            return cachedEntries!!
        }

        val allContacts = contactRepository.getAllContacts()
        val onlineContactIds = presenceRepository.getOnlineUsers().getOrElse { emptySet() }
        val scoreMap = buildScoreMap()

        Log.d(TAG, "Total contacts: ${allContacts.size}, Online: ${onlineContactIds.size}")

        val onlineContacts = allContacts.filter { it.contactId in onlineContactIds }

        val selected = mutableListOf<ContactInfo>()
        val usedIds = mutableSetOf<Int>()

        onlineContacts
            .filter { it.isFavorite }
            .sortedByDescending { scoreMap[it.contactId]?.score ?: 0.0 }
            .forEach {
                if (selected.size < MAX_QUICK_DIAL_SLOTS) { selected.add(it); usedIds.add(it.contactId) }
            }
        Log.d(TAG, "After online favorites: ${selected.size}")

        onlineContacts
            .filter { !it.isFavorite && it.contactId !in usedIds && scoreMap.containsKey(it.contactId) }
            .sortedByDescending { scoreMap[it.contactId]!!.score }
            .forEach {
                if (selected.size < MAX_QUICK_DIAL_SLOTS) { selected.add(it); usedIds.add(it.contactId) }
            }
        Log.d(TAG, "After online scored: ${selected.size}")

        onlineContacts
            .filter { it.contactId !in usedIds }
            .shuffled()
            .forEach {
                if (selected.size < MAX_QUICK_DIAL_SLOTS) { selected.add(it); usedIds.add(it.contactId) }
            }
        Log.d(TAG, "After online random: ${selected.size}")

        val entries = selected.mapIndexed { index, contact ->
            val data = scoreMap[contact.contactId]
            QuickDialEntry(
                contact = contact,
                position = index + 1,
                score = data?.score ?: 0.0,
                callCount = data?.callCount ?: 0
            )
        }

        entries.forEach {
            val online = if (it.contact.contactId in onlineContactIds) "ONLINE" else "offline"
            val fav = if (it.contact.isFavorite) "★" else ""
            Log.d(TAG, "Slot ${it.position}: ${it.contact.username} $fav ($online, score=${it.score}, calls=${it.callCount})")
        }

        cachedEntries = entries
        cacheTimestamp = System.currentTimeMillis()
        return entries
    }

    private suspend fun buildScoreMap(): Map<Int, ContactScore> {
        return try {
            val allCalls = callHistoryRepository.getAllCallHistory()
            val callsWithContacts = allCalls.filter { it.contactId != null }
            if (callsWithContacts.isEmpty()) return emptyMap()

            callsWithContacts
                .groupBy { it.contactId!! }
                .map { (contactId, calls) ->
                    var score = 0.0
                    calls.forEachIndexed { index, call ->
                        val recencyMultiplier = 2.0 - (index * 0.1).coerceAtMost(1.5)
                        val typeWeight = when (call.callType.lowercase()) {
                            "outgoing" -> 1.5
                            "incoming" -> 1.2
                            "missed"   -> 0.5
                            else       -> 1.0
                        }
                        score += recencyMultiplier * typeWeight
                    }
                    ContactScore(contactId, score, calls.size)
                }
                .associateBy { it.contactId }
        } catch (e: Exception) {
            Log.w(TAG, "Could not build score map: ${e.message}")
            emptyMap()
        }
    }

    private data class ContactScore(val contactId: Int, val score: Double, val callCount: Int)
}
