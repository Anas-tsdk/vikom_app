package com.example.tv_caller_app.ui.fragments

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.data.repository.PriorityRepository
import com.example.tv_caller_app.domain.ContactInfo
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.util.AvatarHelper
import com.example.tv_caller_app.ui.viewmodel.QuickDialViewModel

class ManageFavoritesFragment : Fragment() {

    private val viewModel: QuickDialViewModel by activityViewModels()
    private lateinit var adapter: ManageFavoritesAdapter
    private var hasScrolledToEnd = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_manage_favorites, container, false)

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.hideHeader()
    }

    override fun onStop() {
        super.onStop()
        (activity as? MainActivity)?.showHeader()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.manage_favorites_title))

        view.findViewById<TextView>(R.id.btn_go_back).apply {
            setOnClickListener { parentFragmentManager.popBackStack() }
            requestFocus()
        }

        adapter = ManageFavoritesAdapter(
            onToggleFavorite = { contactId, isFavorite ->
                viewModel.toggleFavorite(contactId, isFavorite)
            },
            onTogglePriority = { contactId ->
                viewModel.togglePriority(contactId)
            },
            isPriorityFull = { viewModel.isPriorityFull() }
        )

        val rv = view.findViewById<RecyclerView>(R.id.rv_contacts)
        val scrollIndicator = view.findViewById<LinearLayout>(R.id.scroll_indicator)
        val txtFavCount = view.findViewById<TextView>(R.id.txt_favorites_count)
        val txtPrioCount = view.findViewById<TextView>(R.id.txt_priority_count)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val lm = rv.layoutManager as LinearLayoutManager

        fun focusButtonInRow(targetPos: Int, btnId: Int, direction: Int) {
            fun focusIn(itemView: View) {
                val target = itemView.findViewById<View>(btnId)
                if (target != null && target.visibility == View.VISIBLE && target.isFocusable) {
                    target.requestFocus()
                } else {
                    itemView.findViewById<View>(R.id.btn_favorite_toggle)?.requestFocus()
                }
            }
            val targetView = lm.findViewByPosition(targetPos)
            if (targetView != null) {
                focusIn(targetView)
            } else {
                val padPos = if (direction < 0) {
                    maxOf(0, targetPos - 1)
                } else {
                    minOf(adapter.itemCount - 1, targetPos + 1)
                }
                rv.smoothScrollToPosition(padPos)
                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            rv.removeOnScrollListener(this)
                            lm.findViewByPosition(targetPos)?.let { focusIn(it) }
                        }
                    }
                })
            }
        }

        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(itemView: View) {
                val btnFav = itemView.findViewById<View>(R.id.btn_favorite_toggle)
                val btnPrio = itemView.findViewById<View>(R.id.btn_priority_toggle)

                val favKeyListener = View.OnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                    val pos = rv.getChildAdapterPosition(itemView)
                    if (pos == RecyclerView.NO_POSITION) return@OnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (pos > 0) { focusButtonInRow(pos - 1, R.id.btn_favorite_toggle, -1); true }
                            else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (pos < adapter.itemCount - 1) { focusButtonInRow(pos + 1, R.id.btn_favorite_toggle, +1); true }
                            else false
                        }
                        else -> false
                    }
                }

                val prioKeyListener = View.OnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                    val pos = rv.getChildAdapterPosition(itemView)
                    if (pos == RecyclerView.NO_POSITION) return@OnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (pos > 0) { focusButtonInRow(pos - 1, R.id.btn_priority_toggle, -1); true }
                            else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (pos < adapter.itemCount - 1) { focusButtonInRow(pos + 1, R.id.btn_priority_toggle, +1); true }
                            else false
                        }
                        else -> false
                    }
                }

                btnFav?.setOnKeyListener(favKeyListener)
                btnPrio?.setOnKeyListener(prioKeyListener)
            }

            override fun onChildViewDetachedFromWindow(itemView: View) {
                itemView.findViewById<View>(R.id.btn_favorite_toggle)?.setOnKeyListener(null)
                itemView.findViewById<View>(R.id.btn_priority_toggle)?.setOnKeyListener(null)
            }
        })
        fun updateIndicator() {
            if (hasScrolledToEnd) {
                scrollIndicator.visibility = View.GONE
                return
            }
            val lastVisible = lm.findLastVisibleItemPosition()
            val lastItem = adapter.itemCount - 1
            if (lastItem > 0 && lastVisible >= lastItem) {
                hasScrolledToEnd = true
                scrollIndicator.visibility = View.GONE
            } else {
                scrollIndicator.visibility =
                    if (lastItem > 0 && lastVisible < lastItem) View.VISIBLE else View.GONE
            }
        }

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateIndicator()
            }
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                updateIndicator()
            }
        })

        rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateIndicator() }

        viewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            adapter.submitList(contacts)
            rv.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    rv.viewTreeObserver.removeOnPreDrawListener(this)
                    updateIndicator()
                    return true
                }
            })

            val favCount = contacts.count { it.isFavorite }
            val prioUsed = contacts.count { it.prioritySlot > 0 }
            val prioMax = PriorityRepository.MAX_SLOTS
            val prioFree = prioMax - prioUsed

            txtFavCount.text = getString(R.string.favorites_count, favCount)
            txtPrioCount.text = getString(R.string.top_priority_count, prioUsed, prioMax)
        }
    }

    private fun announceAccessibility(view: View, message: String) {
        val manager = view.context.getSystemService(AccessibilityManager::class.java) ?: return
        if (!manager.isEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val event = AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            event.text.add(message)
            manager.sendAccessibilityEvent(event)
        } else {
            @Suppress("DEPRECATION")
            view.announceForAccessibility(message)
        }
    }

    private inner class ManageFavoritesAdapter(
        private val onToggleFavorite: (String, Boolean) -> Unit,
        private val onTogglePriority: (String) -> Unit,
        private val isPriorityFull: () -> Boolean
    ) : RecyclerView.Adapter<ManageFavoritesAdapter.ViewHolder>() {

        private var contacts: List<ContactInfo> = emptyList()
        private var lastKnownFull: Boolean = false

        fun submitList(newContacts: List<ContactInfo>) {
            val newFull = isPriorityFull()
            val fullnessChanged = newFull != lastKnownFull
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = contacts.size
                override fun getNewListSize() = newContacts.size
                override fun areItemsTheSame(o: Int, n: Int) = contacts[o].id == newContacts[n].id
                override fun areContentsTheSame(o: Int, n: Int) = contacts[o] == newContacts[n]
            })
            contacts = newContacts
            lastKnownFull = newFull
            diff.dispatchUpdatesTo(this)
            if (fullnessChanged) notifyItemRangeChanged(0, itemCount)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(R.id.txt_contact_name)
            val btnFavorite: View = view.findViewById(R.id.btn_favorite_toggle)
            val iconFavorite: ImageView = view.findViewById(R.id.icon_favorite)
            val txtFavoriteLabel: TextView = view.findViewById(R.id.txt_favorite_label)
            val btnPriority: View = view.findViewById(R.id.btn_priority_toggle)
            val iconPriority: ImageView = view.findViewById(R.id.icon_priority)
            val txtPriorityLabel: TextView = view.findViewById(R.id.txt_priority_label)
            val avatarBg: View = view.findViewById(R.id.avatar_bg)
            val avatarImage: ImageView = view.findViewById(R.id.avatar_image)
            val avatarInitial: TextView = view.findViewById(R.id.avatar_initial)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_manage_favorite, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = contacts[position]
            val displayName = if (!contact.nickname.isNullOrBlank())
                "${contact.username} (${contact.nickname})"
            else
                contact.username
            holder.txtName.text = displayName

            if (!contact.avatarUrl.isNullOrBlank()) {
                holder.avatarInitial.visibility = View.GONE
                holder.avatarBg.visibility = View.GONE
                holder.avatarImage.visibility = View.VISIBLE
                AvatarHelper.loadAvatar(holder.avatarImage, contact.avatarUrl)
            } else {
                holder.avatarImage.visibility = View.GONE
                holder.avatarBg.visibility = View.VISIBLE
                holder.avatarInitial.visibility = View.VISIBLE
                holder.avatarInitial.text = contact.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }

            applyFavoriteStyle(holder.btnFavorite, holder.iconFavorite, holder.txtFavoriteLabel, contact.isFavorite)
            applyPriorityStyle(holder.btnPriority, holder.iconPriority, holder.txtPriorityLabel, contact)

            val favLabel = holder.txtFavoriteLabel.text
            holder.btnFavorite.contentDescription = "$displayName – $favLabel"
            val prioLabel = holder.txtPriorityLabel.text
            if (holder.btnPriority.visibility == View.VISIBLE) {
                holder.btnPriority.contentDescription = "$displayName – $prioLabel"
            }

            holder.btnFavorite.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val c = contacts[pos]
                val adding = !c.isFavorite
                val name = if (!c.nickname.isNullOrBlank()) "${c.username} (${c.nickname})" else c.username
                val msg = if (adding) "$name er lagt til som favoritt" else "$name er fjernet som favoritt"
                announceAccessibility(holder.itemView, msg)
                onToggleFavorite(c.id, adding)
            }

            holder.btnPriority.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val c = contacts[pos]
                if (!c.isFavorite) return@setOnClickListener
                if (c.prioritySlot == 0 && isPriorityFull()) return@setOnClickListener
                val name = if (!c.nickname.isNullOrBlank()) "${c.username} (${c.nickname})" else c.username
                val msg = if (c.prioritySlot > 0) "$name er fjernet fra prioritet" else "$name er satt som prioritert"
                announceAccessibility(holder.itemView, msg)
                onTogglePriority(c.id)
                holder.btnPriority.post { holder.btnPriority.requestFocus() }
            }
        }

        private fun applyFavoriteStyle(container: View, icon: ImageView, label: TextView, isFavorite: Boolean) {
            if (isFavorite) {
                label.setText(R.string.favorite)
                container.setBackgroundResource(R.drawable.button_primary)
                icon.setImageResource(R.drawable.ic_star_white)
            } else {
                label.setText(R.string.not_favorite)
                container.setBackgroundResource(R.drawable.button_secondary)
                icon.setImageResource(R.drawable.ic_star_slash_white)
            }
        }

        private fun applyPriorityStyle(container: View, icon: ImageView, label: TextView, contact: ContactInfo) {
            when {
                !contact.isFavorite -> {
                    container.visibility = View.GONE
                }
                contact.prioritySlot > 0 -> {
                    container.visibility = View.VISIBLE
                    container.isEnabled = true
                    container.isFocusable = true
                    container.alpha = 1f
                    label.setText(R.string.priority_selected)
                    container.setBackgroundResource(R.drawable.button_primary)
                    icon.setImageResource(R.drawable.ic_priority_white)
                }
                isPriorityFull() -> {
                    container.visibility = View.VISIBLE
                    container.isEnabled = false
                    container.isFocusable = true
                    container.alpha = 0.4f
                    label.setText(R.string.priority_no_space)
                    container.setBackgroundResource(R.drawable.button_secondary)
                    icon.setImageResource(R.drawable.ic_priority_slash_white)
                }
                else -> {
                    container.visibility = View.VISIBLE
                    container.isEnabled = true
                    container.isFocusable = true
                    container.alpha = 1f
                    label.setText(R.string.priority_not_selected)
                    container.setBackgroundResource(R.drawable.button_secondary)
                    icon.setImageResource(R.drawable.ic_priority_slash_white)
                }
            }
        }

        override fun getItemCount() = contacts.size
    }
}
