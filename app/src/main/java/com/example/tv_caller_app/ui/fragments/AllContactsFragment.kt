package com.example.tv_caller_app.ui.fragments

import android.animation.ObjectAnimator
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.adapters.ContactListAdapter
import com.example.tv_caller_app.domain.ContactInfo
import com.example.tv_caller_app.domain.UserSearchResult
import com.example.tv_caller_app.ui.viewmodel.ContactsViewModel

class AllContactsFragment : Fragment() {

    companion object {
        private const val TAG = "AllContactsFragment"
    }

    private val viewModel: ContactsViewModel by activityViewModels()
    private lateinit var contactsList: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var btnClearSearch: TextView
    private lateinit var btnSearch: TextView

    private var currentContacts: List<ContactInfo> = emptyList()
    private var currentSearchResults: List<UserSearchResult> = emptyList()
    private lateinit var contactsAdapter: ContactListAdapter
    private lateinit var skeletonListAdapter: SkeletonListAdapter
    private var hasLoadedOnce = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_contacts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_contacts))

        contactsList = view.findViewById(R.id.contacts_list)
        searchInput = view.findViewById(R.id.search_input)
        btnClearSearch = view.findViewById(R.id.btn_clear_search)
        btnSearch = view.findViewById(R.id.btn_search)

        setupTabs(view)
        setupList()
        setupSearch()
        observeViewModel()
        if (viewModel.displayedContacts.value.isNullOrEmpty()) {
            viewModel.loadAllContacts()
        }

        val blockDownWhenEmpty = android.view.View.OnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                val hasItems = currentContacts.isNotEmpty() || currentSearchResults.isNotEmpty()
                if (!hasItems) return@OnKeyListener true
            }
            false
        }
        searchInput.setOnKeyListener(blockDownWhenEmpty)
        btnSearch.setOnKeyListener(blockDownWhenEmpty)
        btnClearSearch.setOnKeyListener(blockDownWhenEmpty)

        view.findViewById<TextView>(R.id.tab_contacts)?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshContacts()
    }

    private fun setupTabs(view: View) {
        val tabQuickDial = view.findViewById<TextView>(R.id.tab_quick_dial)
        val tabContacts = view.findViewById<TextView>(R.id.tab_contacts)

        tabQuickDial.setOnClickListener {
            val activity = requireActivity()
            activity.supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, QuickDialFragment())
                .runOnCommit {
                    activity.findViewById<android.widget.FrameLayout>(R.id.main_browse_fragment)?.post {
                        activity.findViewById<TextView>(R.id.tab_quick_dial)?.requestFocus()
                    }
                }
                .commit()
        }

        tabContacts.setOnClickListener {
        }
    }

    private fun setupList() {
        contactsList.layoutManager = LinearLayoutManager(requireContext())
        skeletonListAdapter = SkeletonListAdapter()
        contactsAdapter = ContactListAdapter(
            items = emptyList(),
            onContactClick = { contact -> navigateToContactDetail(contact) },
            onSearchResultClick = { profile -> navigateToSearchResultDetail(profile) }
        )
        val concatConfig = ConcatAdapter.Config.Builder().setIsolateViewTypes(false).build()
        contactsList.adapter = ConcatAdapter(concatConfig, skeletonListAdapter, contactsAdapter)

        contactsList.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        }

        contactsList.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnKeyListener { v, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        val lm = contactsList.layoutManager as? LinearLayoutManager
                        val firstVisible = lm?.findFirstVisibleItemPosition() ?: 1
                        val pos = contactsList.getChildAdapterPosition(v)
                        if (firstVisible <= 1 && pos <= 2) {
                            contactsList.smoothScrollToPosition(0)
                            searchInput.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                    false
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {
                view.setOnKeyListener(null)
            }
        })
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                viewModel.filterContacts(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            searchInput.text.clear()
        }

        btnSearch.setOnClickListener {
            val query = searchInput.text.toString()
            viewModel.filterContacts(query)
            searchInput.clearFocus()
        }
    }

    private fun updateAdapter() {
        if (!hasLoadedOnce) return
        val hasQuery = searchInput.text.isNotBlank()
        val items = if (hasQuery && currentSearchResults.isNotEmpty()) {
            ContactListAdapter.buildCombinedList(currentContacts, currentSearchResults, true)
        } else {
            ContactListAdapter.buildGroupedList(currentContacts)
        }
        contactsAdapter.updateItems(items)
    }

    private fun navigateToContactDetail(contact: ContactInfo) {
        val isInContacts = viewModel.isContactInList(contact.contactId)
        val detailFragment = ContactDetailFragment.newInstance(
            contactId = contact.id,
            contactName = contact.username,
            contactUserId = contact.contactId.toString(),
            contactEmail = contact.email,
            isInContacts = isInContacts,
            isFavorite = contact.isFavorite,
            nickname = contact.nickname,
            avatarUrl = contact.avatarUrl
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, detailFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToSearchResultDetail(profile: UserSearchResult) {
        val detailFragment = ContactDetailFragment.newInstance(
            contactId = "",
            contactName = profile.username,
            contactUserId = profile.contactId.toString(),
            contactEmail = profile.email,
            isInContacts = false,
            isFavorite = false,
            avatarUrl = profile.avatarUrl
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, detailFragment)
            .addToBackStack(null)
            .commit()
    }

    private class SkeletonListAdapter : RecyclerView.Adapter<SkeletonListAdapter.ViewHolder>() {
        companion object {
            const val VIEW_TYPE_HEADER = 500
            const val VIEW_TYPE_CONTACT = 501
            private val PATTERN = intArrayOf(
                VIEW_TYPE_HEADER, VIEW_TYPE_CONTACT,
                VIEW_TYPE_HEADER, VIEW_TYPE_CONTACT,
                VIEW_TYPE_HEADER, VIEW_TYPE_CONTACT,
                VIEW_TYPE_HEADER, VIEW_TYPE_CONTACT
            )
        }

        private var count = 0

        override fun getItemViewType(position: Int) = PATTERN[position]

        fun setVisible(show: Boolean) {
            val newCount = if (show) PATTERN.size else 0
            if (count == newCount) return
            val old = count
            count = newCount
            if (old == 0) notifyItemRangeInserted(0, count)
            else notifyItemRangeRemoved(0, old)
        }

        override fun getItemCount() = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val res = parent.context.resources
            val density = res.displayMetrics.density

            return when (viewType) {
                VIEW_TYPE_HEADER -> {
                    val padTop = (8 * density).toInt()
                    val padBottom = (4 * density).toInt()
                    val container = LinearLayout(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT,
                            RecyclerView.LayoutParams.WRAP_CONTENT
                        )
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, padTop, 0, padBottom)
                    }
                    val letterBar = View(parent.context).apply {
                        layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (14 * density).toInt())
                        setBackgroundResource(R.drawable.skeleton_rect)
                    }
                    container.addView(letterBar)
                    ViewHolder(container)
                }
                else -> {
                    val height = res.getDimensionPixelSize(R.dimen.list_item_height)
                    val padH = res.getDimensionPixelSize(R.dimen.padding_standard)
                    val indicatorSize = (14 * density).toInt()
                    val indicatorMargin = (12 * density).toInt()

                    val row = LinearLayout(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT, height
                        ).apply { bottomMargin = (6 * density).toInt() }
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundResource(R.drawable.skeleton_list_item)
                        setPadding(padH, 0, padH, 0)
                    }
                    val nameBar = View(parent.context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, (16 * density).toInt(), 1f)
                        setBackgroundResource(R.drawable.skeleton_rect)
                    }
                    row.addView(nameBar)
                    val indicator = View(parent.context).apply {
                        layoutParams = LinearLayout.LayoutParams(indicatorSize, indicatorSize).apply {
                            marginStart = indicatorMargin
                        }
                        setBackgroundResource(R.drawable.skeleton_circle)
                    }
                    row.addView(indicator)
                    ViewHolder(row)
                }
            }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.startPulse() }
        override fun onViewDetachedFromWindow(holder: ViewHolder) { holder.stopPulse() }
        override fun onViewRecycled(holder: ViewHolder) { holder.stopPulse() }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private var animator: ObjectAnimator? = null
            fun startPulse() {
                animator?.cancel()
                animator = ObjectAnimator.ofFloat(itemView, "alpha", 1f, 0.3f).apply {
                    duration = 900
                    repeatMode = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
            fun stopPulse() { animator?.cancel(); animator = null; itemView.alpha = 1f }
        }
    }

    private fun observeViewModel() {
        viewModel.displayedContacts.observe(viewLifecycleOwner) { contacts ->
            Log.d(TAG, "Contacts updated: ${contacts.size}")
            currentContacts = contacts
            updateAdapter()
        }

        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            Log.d(TAG, "Search results updated: ${results.size}")
            currentSearchResults = results
            updateAdapter()
        }

        viewModel.contactAdded.observe(viewLifecycleOwner) { added ->
            if (added) {
                Toast.makeText(requireContext(), getString(R.string.contact_added), Toast.LENGTH_SHORT).show()
                viewModel.resetContactAdded()
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            Log.d(TAG, "Loading state: $isLoading")
            if (isLoading && !hasLoadedOnce) {
                skeletonListAdapter.setVisible(true)
            } else if (!isLoading && !hasLoadedOnce) {
                skeletonListAdapter.setVisible(false)
                hasLoadedOnce = true
                updateAdapter()
            }
        }
    }
}
