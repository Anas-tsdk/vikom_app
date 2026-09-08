package com.example.tv_caller_app.ui.adapters

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.toColorInt
import com.example.tv_caller_app.R
import com.example.tv_caller_app.domain.ContactInfo
import com.example.tv_caller_app.ui.util.AvatarHelper


class ContactGridAdapter(
    private var contacts: List<ContactInfo>,
    private val cardType: CardType = CardType.QUICK_DIAL,
    private val onContactClick: (ContactInfo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class CardType {
        QUICK_DIAL,
        FAVORITE
    }

    companion object {
        const val VIEW_TYPE_EMPTY = 999
    }

    private var isLoading = false

    fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        val oldCount = getItemCount()
        isLoading = loading
        val newCount = getItemCount()
        when {
            oldCount == 0 && newCount > 0 -> notifyItemRangeInserted(0, newCount)
            oldCount > 0 && newCount == 0 -> notifyItemRangeRemoved(0, oldCount)
            oldCount != newCount -> notifyDataSetChanged()
        }
    }

    fun updateContacts(newContacts: List<ContactInfo>) {
        if (isLoading) {
            contacts = newContacts
            return
        }
        val wasEmpty = contacts.isEmpty()
        val willBeEmpty = newContacts.isEmpty()
        if (wasEmpty != willBeEmpty) {
            contacts = newContacts
            notifyDataSetChanged()
        } else {
            val diff = DiffUtil.calculateDiff(ContactDiffCallback(contacts, newContacts))
            contacts = newContacts
            diff.dispatchUpdatesTo(this)
        }
    }

    private fun createCardViewHolder(parent: ViewGroup): ContactViewHolder {
        val res = parent.context.resources
        val density = res.displayMetrics.density
        val avatarSize = res.getDimensionPixelSize(R.dimen.avatar_size_contact_card)

        val rootFrame = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = (4 * density).toInt()
                setMargins(m, m, m, m)
            }
        }

        val cardLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.contact_card_favorite)
            tag = "card_root"
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
        }

        val avatarContainer = FrameLayout(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * density).toInt()
            }
        }

        val avatarBg = View(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(
                if (cardType == CardType.QUICK_DIAL) R.drawable.avatar_circle_card_quickdial
                else R.drawable.avatar_circle_card_favorite
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "avatar_bg"
        }

        val avatarImage = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "avatar_image"
        }

        val avatarInitial = TextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "avatar_initial"
        }

        val avatarRing = View(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(
                if (cardType == CardType.QUICK_DIAL) R.drawable.avatar_ring_quickdial
                else R.drawable.avatar_ring_favorite
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        avatarContainer.addView(avatarBg)
        avatarContainer.addView(avatarImage)
        avatarContainer.addView(avatarInitial)
        avatarContainer.addView(avatarRing)
        cardLayout.addView(avatarContainer)

        val textColor = if (cardType == CardType.QUICK_DIAL) "#0A2E0D".toColorInt() else "#06195C".toColorInt()

        val nameText = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.text_contact_card_name))
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "contact_name"
        }
        cardLayout.addView(nameText)

        val subtitleText = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.text_contact_card_subtitle))
            setTextColor(textColor)
            gravity = Gravity.CENTER
            maxLines = 1
            val padH = (8 * density).toInt()
            setPadding(padH, 0, padH, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "contact_subtitle"
        }
        cardLayout.addView(subtitleText)

        rootFrame.addView(cardLayout)

        val badgeMargin = (6 * density).toInt()
        val badgeSize = (20 * density).toInt()
        val badge = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END).apply {
                setMargins(0, badgeMargin, badgeMargin, 0)
            }
            setImageResource(R.drawable.ic_pin)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "priority_badge"
            visibility = View.GONE
        }
        rootFrame.addView(badge)

        return ContactViewHolder(rootFrame)
    }

    override fun getItemViewType(position: Int) =
        if (!isLoading && contacts.isEmpty()) VIEW_TYPE_EMPTY else cardType.ordinal

    override fun getItemCount() = when {
        isLoading -> 0
        contacts.isEmpty() -> 1
        else -> contacts.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_EMPTY) {
            val res = parent.context.resources
            val density = res.displayMetrics.density
            val avatarPx = res.getDimensionPixelSize(R.dimen.avatar_size_contact_card)
            val cardPad = (16 * density).toInt()
            val itemMargin = (4 * density).toInt()
            val nameTextPx = res.getDimensionPixelSize(R.dimen.text_contact_card_name)
            val subtitleTextPx = res.getDimensionPixelSize(R.dimen.text_contact_card_subtitle)
            val cardHeight = cardPad + avatarPx + (8 * density).toInt() +
                    nameTextPx + (4 * density).toInt() + subtitleTextPx + cardPad
            val emptyText = if (cardType == CardType.QUICK_DIAL)
                res.getString(R.string.no_contacts_online)
            else
                res.getString(R.string.no_favorites)
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    cardHeight
                ).apply { setMargins(itemMargin, itemMargin, itemMargin, itemMargin) }
                gravity = Gravity.CENTER
                text = emptyText
                setTextColor(res.getColor(R.color.text_secondary, null))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.text_primary))
            }
            return EmptyViewHolder(tv)
        }
        return createCardViewHolder(parent)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ContactViewHolder) holder.bind(contacts[position], onContactClick)
    }

    inner class EmptyViewHolder(view: View) : RecyclerView.ViewHolder(view)

    inner class ContactViewHolder(private val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        fun bind(contact: ContactInfo, onClick: (ContactInfo) -> Unit) {
            val cardLayout = root.findViewWithTag<LinearLayout>("card_root")
            val nameView = root.findViewWithTag<TextView>("contact_name")
            val subtitleView = root.findViewWithTag<TextView>("contact_subtitle")
            val avatarInitial = root.findViewWithTag<TextView>("avatar_initial")
            val avatarImage = root.findViewWithTag<ImageView>("avatar_image")
            val avatarBg = root.findViewWithTag<View>("avatar_bg")
            val badge = root.findViewWithTag<ImageView>("priority_badge")

            val bgRes = when {
                cardType == CardType.QUICK_DIAL -> R.drawable.contact_card_quickdial
                contact.prioritySlot > 0 -> R.drawable.contact_card_priority
                else -> R.drawable.contact_card_favorite
            }
            cardLayout?.setBackgroundResource(bgRes)

            nameView?.text = contact.username
            subtitleView?.text = contact.nickname ?: ""

            val desc = if (!contact.nickname.isNullOrBlank())
                "${contact.username}, ${contact.nickname}"
            else
                contact.username
            root.contentDescription = desc
            cardLayout?.contentDescription = desc

            if (!contact.avatarUrl.isNullOrBlank()) {
                avatarInitial?.visibility = View.GONE
                avatarBg?.visibility = View.GONE
                avatarImage?.visibility = View.VISIBLE
                avatarImage?.let { AvatarHelper.loadAvatar(it, contact.avatarUrl) }
            } else {
                avatarImage?.visibility = View.GONE
                avatarBg?.visibility = View.VISIBLE
                avatarInitial?.visibility = View.VISIBLE
                avatarInitial?.text = contact.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }

            badge?.visibility =
                if (cardType == CardType.FAVORITE && contact.prioritySlot > 0) View.VISIBLE else View.GONE

            cardLayout?.setOnClickListener { onClick(contact) }
        }
    }

    private class ContactDiffCallback(
        private val oldList: List<ContactInfo>,
        private val newList: List<ContactInfo>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldList[oldPos].id == newList[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = oldList[oldPos] == newList[newPos]
    }
}
