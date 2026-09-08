package com.example.tv_caller_app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.domain.ContactInfo
import com.example.tv_caller_app.domain.UserSearchResult

sealed class ContactListItem {
    data class SectionHeader(val letter: Char) : ContactListItem()
    data class TextHeader(@StringRes val titleRes: Int) : ContactListItem()
    data class ContactEntry(val contact: ContactInfo) : ContactListItem()
    data class SearchResultEntry(val profile: UserSearchResult) : ContactListItem()
    object Empty : ContactListItem()
}

class ContactListAdapter(
    items: List<ContactListItem>,
    private val onContactClick: (ContactInfo) -> Unit,
    private val onSearchResultClick: ((UserSearchResult) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ContactListItem> = items

    fun updateItems(newItems: List<ContactListItem>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = items[oldPos]; val new = newItems[newPos]
                return when {
                    old is ContactListItem.SectionHeader && new is ContactListItem.SectionHeader -> old.letter == new.letter
                    old is ContactListItem.TextHeader && new is ContactListItem.TextHeader -> old.titleRes == new.titleRes
                    old is ContactListItem.ContactEntry && new is ContactListItem.ContactEntry -> old.contact.id == new.contact.id
                    old is ContactListItem.SearchResultEntry && new is ContactListItem.SearchResultEntry -> old.profile.contactId == new.profile.contactId
                    else -> false
                }
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = items[oldPos] == newItems[newPos]
        })
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_CONTACT = 1
        const val VIEW_TYPE_SEARCH_RESULT = 2
        const val VIEW_TYPE_TEXT_HEADER = 3
        const val VIEW_TYPE_EMPTY = 4

        fun buildGroupedList(contacts: List<ContactInfo>): List<ContactListItem> {
            if (contacts.isEmpty()) return listOf(ContactListItem.Empty)
            val sorted = contacts.sortedBy { it.username.lowercase() }
            val items = mutableListOf<ContactListItem>()
            var lastLetter: Char? = null

            for (contact in sorted) {
                val firstChar = contact.username.firstOrNull()?.uppercaseChar() ?: '?'
                if (firstChar != lastLetter) {
                    items.add(ContactListItem.SectionHeader(firstChar))
                    lastLetter = firstChar
                }
                items.add(ContactListItem.ContactEntry(contact))
            }
            return items
        }

        fun buildCombinedList(
            contacts: List<ContactInfo>,
            searchResults: List<UserSearchResult>,
            hasQuery: Boolean
        ): List<ContactListItem> {
            val items = mutableListOf<ContactListItem>()

            if (contacts.isNotEmpty()) {
                if (hasQuery && searchResults.isNotEmpty()) {
                    items.add(ContactListItem.TextHeader(R.string.section_your_contacts))
                }
                val sorted = contacts.sortedBy { it.username.lowercase() }
                var lastLetter: Char? = null
                for (contact in sorted) {
                    val firstChar = contact.username.firstOrNull()?.uppercaseChar() ?: '?'
                    if (firstChar != lastLetter) {
                        items.add(ContactListItem.SectionHeader(firstChar))
                        lastLetter = firstChar
                    }
                    items.add(ContactListItem.ContactEntry(contact))
                }
            }

            if (searchResults.isNotEmpty()) {
                items.add(ContactListItem.TextHeader(R.string.section_other_users))
                for (profile in searchResults) {
                    items.add(ContactListItem.SearchResultEntry(profile))
                }
            }

            return items
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ContactListItem.SectionHeader -> VIEW_TYPE_HEADER
            is ContactListItem.TextHeader -> VIEW_TYPE_TEXT_HEADER
            is ContactListItem.ContactEntry -> VIEW_TYPE_CONTACT
            is ContactListItem.SearchResultEntry -> VIEW_TYPE_SEARCH_RESULT
            is ContactListItem.Empty -> VIEW_TYPE_EMPTY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_contact_section_header, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_TEXT_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_contact_section_header, parent, false)
                TextHeaderViewHolder(view)
            }
            VIEW_TYPE_SEARCH_RESULT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_search_result, parent, false)
                SearchResultViewHolder(view)
            }
            VIEW_TYPE_EMPTY -> {
                val tv = android.widget.TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                    val pad = (32 * parent.context.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    gravity = android.view.Gravity.CENTER
                    text = parent.context.getString(R.string.no_contacts_empty_state)
                    setTextColor(parent.context.getColor(R.color.text_secondary))
                    textSize = 16f
                }
                EmptyViewHolder(tv)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_contact_list, parent, false)
                ContactViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ContactListItem.SectionHeader -> (holder as HeaderViewHolder).bind(item.letter)
            is ContactListItem.TextHeader -> (holder as TextHeaderViewHolder).bind(item.titleRes)
            is ContactListItem.ContactEntry -> (holder as ContactViewHolder).bind(item.contact, onContactClick)
            is ContactListItem.SearchResultEntry -> (holder as SearchResultViewHolder).bind(item.profile, onSearchResultClick)
            is ContactListItem.Empty -> Unit
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sectionLetter: TextView = itemView.findViewById(R.id.section_letter)
        fun bind(letter: Char) { sectionLetter.text = letter.toString() }
    }

    class TextHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sectionLetter: TextView = itemView.findViewById(R.id.section_letter)
        fun bind(@StringRes titleRes: Int) { sectionLetter.text = itemView.context.getString(titleRes) }
    }

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val contactName: TextView = itemView.findViewById(R.id.contact_name)
        private val onlineIndicator: View = itemView.findViewById(R.id.online_indicator)

        fun bind(contact: ContactInfo, onClick: (ContactInfo) -> Unit) {
            contactName.text = if (!contact.nickname.isNullOrBlank()) {
                itemView.context.getString(R.string.contact_name_with_nickname, contact.username, contact.nickname)
            } else {
                contact.username
            }
            onlineIndicator.visibility = if (contact.isOnline) View.VISIBLE else View.INVISIBLE
            itemView.setOnClickListener { onClick(contact) }
        }
    }

    class EmptyViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class SearchResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val username: TextView = itemView.findViewById(R.id.search_result_username)
        private val contactId: TextView = itemView.findViewById(R.id.search_result_contact_id)

        fun bind(profile: UserSearchResult, onClick: ((UserSearchResult) -> Unit)?) {
            username.text = profile.username.ifBlank { itemView.context.getString(R.string.unknown_contact) }
            contactId.text = itemView.context.getString(R.string.contact_id_format, profile.contactId)
            itemView.setOnClickListener { onClick?.invoke(profile) }
        }
    }
}
