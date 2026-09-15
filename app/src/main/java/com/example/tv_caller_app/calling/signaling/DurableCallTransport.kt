package com.example.tv_caller_app.calling.signaling

import android.util.Log
import com.example.tv_caller_app.BuildConfig
import com.example.tv_caller_app.calling.webrtc.WebRTCConfig
import com.example.tv_caller_app.data.remote.BackendApiClient
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Authenticated HTTPS transport for production call signaling. Messages are
 * durable and recoverable when WebSockets are blocked or a tablet was
 * temporarily offline; Realtime remains a local-development fallback.
 *
 * Every method is best-effort so development projects that have not deployed
 * the Edge Function continue to use realtime calling unchanged.
 */
class DurableCallTransport(
    private val supabase: SupabaseClient,
    private val accessTokenProvider: () -> String? = {
        supabase.auth.currentSessionOrNull()?.accessToken
    }
) {
    companion object {
        private const val TAG = "DurableCallTransport"
        private const val FUNCTION_NAME = "call-infrastructure"
    }

    @Serializable
    data class Event(
        val id: Long,
        @SerialName("call_id") val callId: String,
        @SerialName("sender_id") val senderId: String,
        @SerialName("event_type") val eventType: String,
        val payload: JsonObject,
        @SerialName("created_at") val createdAt: String
    )

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var unavailableUntil = 0L

    suspend fun send(
        targetUserId: String,
        type: String,
        encodedPayload: String
    ): Boolean {
        if (System.currentTimeMillis() < unavailableUntil) return false
        return runCatching {
        val payloadObject = json.parseToJsonElement(encodedPayload).jsonObject
        val response = invoke(
            buildJsonObject {
                put("action", "send")
                put("targetUserId", targetUserId)
                put("type", type)
                put("payload", payloadObject)
            }
        )
        if (response.status.value !in 200..299) {
            throw IllegalStateException("HTTP ${response.status.value}")
        }
        unavailableUntil = 0L
        true
        }.onFailure(::recordFailure).getOrDefault(false)
    }

    suspend fun poll(): List<Event> {
        if (System.currentTimeMillis() < unavailableUntil) return emptyList()
        return runCatching {
            val response = invoke(buildJsonObject { put("action", "poll") })
            if (response.status.value !in 200..299) {
                throw IllegalStateException("HTTP ${response.status.value}")
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val events = root["events"]?.jsonArray ?: JsonArray(emptyList())
            unavailableUntil = 0L
            events.map { json.decodeFromJsonElement(Event.serializer(), it) }
        }.onFailure(::recordFailure).getOrDefault(emptyList())
    }

    suspend fun acknowledge(ids: List<Long>) {
        if (ids.isEmpty()) return
        runCatching {
            val response = invoke(
                buildJsonObject {
                    put("action", "ack")
                    put("ids", JsonArray(ids.map(::JsonPrimitive)))
                }
            )
            if (response.status.value !in 200..299) {
                throw IllegalStateException("HTTP ${response.status.value}")
            }
        }.onFailure(::recordFailure)
    }

    suspend fun refreshTurnConfiguration(): Boolean {
        if (System.currentTimeMillis() < unavailableUntil) return false
        return runCatching {
            val response = invoke(buildJsonObject { put("action", "turn") })
            if (response.status.value !in 200..299) {
                throw IllegalStateException("HTTP ${response.status.value}")
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val servers = root["iceServers"]?.jsonArray.orEmpty().mapNotNull { element ->
                val value = element.jsonObject
                val urlsElement = value["urls"]
                val urls = when (urlsElement) {
                    is JsonArray -> urlsElement.mapNotNull { it.jsonPrimitive.contentOrNull }
                    is JsonPrimitive -> listOfNotNull(urlsElement.contentOrNull)
                    else -> emptyList()
                }.filter { url ->
                    url.startsWith("stun:") || url.startsWith("turn:") || url.startsWith("turns:")
                }
                urls.takeIf { it.isNotEmpty() }?.let {
                    WebRTCConfig.ManagedIceServer(
                        urls = it,
                        username = value["username"]?.jsonPrimitive?.contentOrNull,
                        credential = value["credential"]?.jsonPrimitive?.contentOrNull
                    )
                }
            }
            // A successful empty response deliberately clears credentials that
            // may have expired since the last call. LAN/STUN calling still has
            // its fallback; different networks require a configured relay.
            WebRTCConfig.updateManagedIceServers(servers)
            servers.isNotEmpty()
        }.onFailure { error ->
            Log.w(TAG, "Managed TURN credentials unavailable: ${error::class.simpleName}")
        }.getOrDefault(false)
    }

    private fun recordFailure(error: Throwable) {
        // Missing function/table is common until the one-time Supabase setup is
        // applied. Avoid hammering the endpoint or filling logcat every second.
        unavailableUntil = System.currentTimeMillis() + 30_000L
        Log.w(TAG, "Durable call fallback unavailable: ${error::class.simpleName}")
    }

    private suspend fun invoke(body: JsonObject): HttpResponse {
        val accessToken = accessTokenProvider() ?: error("No active Supabase session")
        val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') +
            "/functions/v1/$FUNCTION_NAME"
        return BackendApiClient.client.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_KEY)
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body.toString())
        }
    }
}
