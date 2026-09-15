package com.example.tv_caller_app.ui.fragments

import android.animation.ObjectAnimator
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.adapters.ContactGridAdapter
import com.example.tv_caller_app.domain.ContactInfo
import com.example.tv_caller_app.ui.viewmodel.QuickDialViewModel
import com.example.tv_caller_app.ui.activities.MainActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class QuickDialFragment : Fragment() {

    private val viewModel: QuickDialViewModel by activityViewModels()

    companion object {
        private const val TAG = "QuickDialFragment"
        private const val POLL_INTERVAL_MS = 10000L
    }

    private lateinit var mainGrid: RecyclerView

    private val scrollToTopFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus == null) return@OnGlobalFocusChangeListener
        val pos = findRvChildPosition(newFocus)
        if (pos == RecyclerView.NO_POSITION) return@OnGlobalFocusChangeListener
        val cols = (mainGrid.layoutManager as? GridLayoutManager)?.spanCount ?: return@OnGlobalFocusChangeListener
        if (pos in 1..cols) {
            mainGrid.smoothScrollToPosition(0)
        }
    }

    private fun findRvChildPosition(focused: View): Int {
        var v: View? = focused
        while (v != null) {
            val parent = v.parent
            if (parent === mainGrid) return mainGrid.getChildAdapterPosition(v)
            v = parent as? View
        }
        return RecyclerView.NO_POSITION
    }

    private lateinit var quickDialAdapter: ContactGridAdapter
    private lateinit var favoritesAdapter: ContactGridAdapter
    private lateinit var sectionAdapter: SectionDividerAdapter
    private lateinit var statusHeaderAdapter: StatusHeaderAdapter
    private lateinit var skeletonOnlineAdapter: SkeletonContactAdapter
    private lateinit var skeletonFavAdapter: SkeletonContactAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private var gridColumns = 4
    private var hasLoadedOnce = false

    private val appForegroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (isAdded && isVisible && !isRemoving) {
                viewModel.refreshQuickDialContacts()
                viewModel.loadOnlineCount()
            }
        }
    }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            viewModel.loadOnlineCount()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d(TAG, "onCreateView called")
        return inflater.inflate(R.layout.fragment_quick_dial, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_home))
        Log.d(TAG, "onViewCreated called")

        view.findViewById<TextView>(R.id.txt_screen_title).setText(R.string.tab_quick_dial)
        view.findViewById<View>(R.id.btn_screen_back).setOnClickListener {
            (activity as? MainActivity)?.showHome()
        }

        mainGrid = view.findViewById(R.id.main_grid)

        setupTabs(view)
        setupGrid()
        observeViewModel()
        view.viewTreeObserver.addOnGlobalFocusChangeListener(scrollToTopFocusListener)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundObserver)

        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            val focused = mainGrid.findFocus() ?: return@setOnKeyListener false
            val pos = findRvChildPosition(focused)
            if (pos in 1 until 1 + gridColumns) {
                view.findViewById<TextView>(R.id.tab_quick_dial)?.requestFocus()
                return@setOnKeyListener true
            }
            false
        }

        view.findViewById<TextView>(R.id.tab_quick_dial)?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        viewModel.refreshQuickDialContacts()
        viewModel.loadOnlineCount()
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(scrollToTopFocusListener)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appForegroundObserver)
    }

    private fun setupTabs(view: View) {
        val tabQuickDial = view.findViewById<TextView>(R.id.tab_quick_dial)
        val tabContacts = view.findViewById<TextView>(R.id.tab_contacts)

        tabQuickDial.setOnClickListener { }

        tabContacts.setOnClickListener {
            val activity = requireActivity()
            activity.supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, AllContactsFragment())
                .runOnCommit {
                    activity.findViewById<android.widget.FrameLayout>(R.id.main_browse_fragment)?.post {
                        activity.findViewById<TextView>(R.id.tab_contacts)?.requestFocus()
                    }
                }
                .commit()
        }
    }

    private fun setupGrid() {
        val isLargeScreen = resources.getBoolean(R.bool.is_large_screen)
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Preserve the four-column TV layout while keeping large, readable touch
        // targets on portrait tablets and phones.
        val columns = when {
            isTv || (isLargeScreen && isLandscape) -> 4
            isLandscape -> 3
            else -> 2
        }
        gridColumns = columns

        quickDialAdapter = ContactGridAdapter(
            emptyList(), ContactGridAdapter.CardType.QUICK_DIAL
        ) { navigateToContactDetail(it) }

        favoritesAdapter = ContactGridAdapter(
            emptyList(), ContactGridAdapter.CardType.FAVORITE
        ) { navigateToContactDetail(it) }

        skeletonOnlineAdapter = SkeletonContactAdapter(columns, ContactGridAdapter.CardType.QUICK_DIAL)
        skeletonFavAdapter = SkeletonContactAdapter(columns, ContactGridAdapter.CardType.FAVORITE)

        sectionAdapter = SectionDividerAdapter(
            onManageFavoritesClick = { showManageFavoritesDialog() },
            onNavigateUp = {
                val lm = mainGrid.layoutManager as? GridLayoutManager ?: return@SectionDividerAdapter
                val hasRealOnline = viewModel.onlineDisplayContacts.value?.isNotEmpty() == true
                if (hasRealOnline) {
                    val target = 1
                    val targetView = lm.findViewByPosition(target)
                    if (targetView != null) {
                        targetView.requestFocus()
                    } else {
                        mainGrid.smoothScrollToPosition(target)
                        mainGrid.post { lm.findViewByPosition(target)?.requestFocus() }
                    }
                } else {
                    this@QuickDialFragment.view?.findViewById<TextView>(R.id.tab_quick_dial)?.requestFocus()
                }
            },
            onNavigateDown = {
                val lm = mainGrid.layoutManager as? GridLayoutManager ?: return@SectionDividerAdapter
                val hasFavorites = viewModel.allFavorites.value?.isNotEmpty() == true
                if (!hasFavorites) return@SectionDividerAdapter
                val firstFavPos = quickDialAdapter.itemCount + 2
                val targetView = lm.findViewByPosition(firstFavPos)
                if (targetView != null) {
                    targetView.requestFocus()
                } else {
                    mainGrid.smoothScrollToPosition(firstFavPos)
                    mainGrid.post { lm.findViewByPosition(firstFavPos)?.requestFocus() }
                }
            }
        )
        statusHeaderAdapter = StatusHeaderAdapter()

        val config = ConcatAdapter.Config.Builder().setIsolateViewTypes(false).build()
        concatAdapter = ConcatAdapter(
            config,
            statusHeaderAdapter,
            skeletonOnlineAdapter,
            quickDialAdapter,
            sectionAdapter,
            skeletonFavAdapter,
            favoritesAdapter
        )
        mainGrid.adapter = concatAdapter
        mainGrid.itemAnimator = null

        val lm = GridLayoutManager(requireContext(), columns)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val type = concatAdapter.getItemViewType(position)
                return if (type == SectionDividerAdapter.VIEW_TYPE || type == StatusHeaderAdapter.VIEW_TYPE || type == ContactGridAdapter.VIEW_TYPE_EMPTY) columns else 1
            }
        }
        mainGrid.layoutManager = lm

        attachRightEdgeGuard(mainGrid, lm)

        mainGrid.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val focused = mainGrid.findFocus() ?: return@setOnKeyListener false
            val pos = findRvChildPosition(focused)
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            val cols = lm.spanCount
            val onlineCount = quickDialAdapter.itemCount

            fun focusManageButton() {
                val sectionPos = onlineCount + 1
                val sectionView = lm.findViewByPosition(sectionPos)
                if (sectionView != null) {
                    sectionView.findViewById<View>(R.id.btn_manage_favorites)?.requestFocus()
                } else {
                    mainGrid.smoothScrollToPosition(sectionPos)
                    mainGrid.post {
                        lm.findViewByPosition(sectionPos)
                            ?.findViewById<View>(R.id.btn_manage_favorites)
                            ?.requestFocus()
                    }
                }
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (pos in 1 until 1 + cols) {
                        view?.findViewById<TextView>(R.id.tab_quick_dial)?.requestFocus()
                        return@setOnKeyListener true
                    }
                    val firstFavPos = onlineCount + 2
                    if (pos in firstFavPos until firstFavPos + cols) {
                        focusManageButton()
                        return@setOnKeyListener true
                    }
                    false
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (onlineCount < 1) return@setOnKeyListener false
                    val lastRowStart = 1 + ((onlineCount - 1) / cols) * cols
                    if (pos in lastRowStart..onlineCount) {
                        focusManageButton()
                        return@setOnKeyListener true
                    }
                    false
                }
                else -> false
            }
        }
    }

    private fun attachRightEdgeGuard(rv: RecyclerView, lm: GridLayoutManager) {
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(rootView: View) {
                val pos = rv.getChildAdapterPosition(rootView)
                val cols = lm.spanCount
                val cardLayout = (rootView as? ViewGroup)?.findViewWithTag<View>("card_root")

                if (cardLayout != null) {
                    cardLayout.nextFocusUpId =
                        if (pos in 1 until 1 + cols) R.id.tab_quick_dial else View.NO_ID
                }

                val keyListener = View.OnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                    val pos = rv.getChildAdapterPosition(rootView)
                    if (pos == RecyclerView.NO_POSITION) return@OnKeyListener false
                    val cols = lm.spanCount
                    val onlineCount = quickDialAdapter.itemCount

                    fun focusManage() {
                        val sectionPos = onlineCount + 1
                        val sv = lm.findViewByPosition(sectionPos)
                        if (sv != null) sv.findViewById<View>(R.id.btn_manage_favorites)?.requestFocus()
                        else {
                            rv.smoothScrollToPosition(sectionPos)
                            rv.post { lm.findViewByPosition(sectionPos)?.findViewById<View>(R.id.btn_manage_favorites)?.requestFocus() }
                        }
                    }

                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (pos in 1 until 1 + cols) {
                                this@QuickDialFragment.view?.findViewById<TextView>(R.id.tab_quick_dial)?.requestFocus()
                                return@OnKeyListener true
                            }
                            val firstFavPos = onlineCount + 2
                            if (pos in firstFavPos until firstFavPos + cols) {
                                focusManage()
                                return@OnKeyListener true
                            }
                            false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (onlineCount < 1) return@OnKeyListener false
                            val lastRowStart = 1 + ((onlineCount - 1) / cols) * cols
                            if (pos in lastRowStart..onlineCount) {
                                focusManage()
                                return@OnKeyListener true
                            }
                            false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val spanSize = lm.spanSizeLookup.getSpanSize(pos)
                            if (spanSize == lm.spanCount) return@OnKeyListener true
                            val itemCount = rv.adapter?.itemCount ?: 0
                            val isLastInRow = pos % cols == cols - 1 || pos == itemCount - 1
                            if (!isLastInRow) return@OnKeyListener false
                            val nextPos = pos + 1
                            if (nextPos < itemCount) {
                                rv.post {
                                    lm.findViewByPosition(nextPos)?.requestFocus()
                                        ?: rv.smoothScrollToPosition(nextPos)
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
                rootView.setOnKeyListener(keyListener)
                (rootView as? ViewGroup)?.findViewWithTag<View>("card_root")?.setOnKeyListener(keyListener)
            }
            override fun onChildViewDetachedFromWindow(rootView: View) {
                val cardLayout = (rootView as? ViewGroup)?.findViewWithTag<View>("card_root")
                cardLayout?.nextFocusUpId = View.NO_ID
                rootView.setOnKeyListener(null)
                cardLayout?.setOnKeyListener(null)
            }
        })
    }

    private fun updateContactGrids() {
        if (!hasLoadedOnce) return

        val onlineContacts = viewModel.onlineDisplayContacts.value ?: emptyList()
        val allFavorites = viewModel.allFavorites.value ?: emptyList()

        Log.d(TAG, "Online: ${onlineContacts.size}, Favorites: ${allFavorites.size}")

        quickDialAdapter.updateContacts(onlineContacts)
        favoritesAdapter.updateContacts(allFavorites)
    }

    private fun observeViewModel() {
        viewModel.onlineDisplayContacts.observe(viewLifecycleOwner) {
            Log.d(TAG, "Online display contacts updated: ${it.size}")
            updateContactGrids()
        }

        viewModel.allFavorites.observe(viewLifecycleOwner) {
            Log.d(TAG, "All favorites updated: ${it.size}")
            sectionAdapter.setFavoriteCount(it.size)
            updateContactGrids()
        }

        viewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            sectionAdapter.setButtonEnabled(contacts.isNotEmpty())
        }

        viewModel.onlineCount.observe(viewLifecycleOwner) { count ->
            statusHeaderAdapter.setOnlineCount(count)
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Log.w(TAG, "Error message: $it")
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        val hasExistingData = viewModel.onlineDisplayContacts.value?.isNotEmpty() == true ||
                viewModel.allFavorites.value?.isNotEmpty() == true
        if (hasExistingData) {
            hasLoadedOnce = true
            updateContactGrids()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            Log.d(TAG, "Loading state: $isLoading")
            if (isLoading && !hasLoadedOnce) {
                skeletonOnlineAdapter.setVisible(gridColumns)
                skeletonFavAdapter.setVisible(gridColumns)
                quickDialAdapter.setLoading(true)
                favoritesAdapter.setLoading(true)
            } else if (!isLoading && !hasLoadedOnce) {
                skeletonOnlineAdapter.setVisible(0)
                skeletonFavAdapter.setVisible(0)
                quickDialAdapter.setLoading(false)
                favoritesAdapter.setLoading(false)
                hasLoadedOnce = true
                mainGrid.post { if (isAdded && !isDetached) updateContactGrids() }
            } else if (!isLoading) {
                updateContactGrids()
            }
        }
    }

    private fun showManageFavoritesDialog() {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, ManageFavoritesFragment())
            .addToBackStack(null)
            .commit()
    }

    private class StatusHeaderAdapter : RecyclerView.Adapter<StatusHeaderAdapter.ViewHolder>() {

        companion object {
            const val VIEW_TYPE = 200
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtStatus: TextView = view.findViewById(R.id.txt_status)
        }

        private var onlineCount = 0
        private var hidden = false

        fun setHidden(hide: Boolean) {
            if (hidden == hide) return
            hidden = hide
            if (hide) notifyItemRemoved(0) else notifyItemInserted(0)
        }

        fun setOnlineCount(count: Int) {
            onlineCount = count
            if (!hidden) notifyItemChanged(0)
        }

        override fun getItemViewType(position: Int) = VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_quick_dial_header, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val dotDrawable = if (onlineCount > 0) R.drawable.ic_status_online else R.drawable.ic_status_offline
            holder.txtStatus.setText(R.string.status_logged_in)
            holder.txtStatus.setCompoundDrawablesWithIntrinsicBounds(dotDrawable, 0, 0, 0)
        }

        override fun getItemCount() = if (hidden) 0 else 1
    }

    private class SectionDividerAdapter(
        private val onManageFavoritesClick: () -> Unit,
        private val onNavigateUp: () -> Unit,
        private val onNavigateDown: () -> Unit
    ) : RecyclerView.Adapter<SectionDividerAdapter.ViewHolder>() {

        companion object {
            const val VIEW_TYPE = 100
        }

        private var buttonEnabled = true
        private var favoriteCount = 0

        fun setButtonEnabled(enabled: Boolean) {
            if (buttonEnabled == enabled) return
            buttonEnabled = enabled
            notifyItemChanged(0)
        }

        fun setFavoriteCount(count: Int) {
            if (favoriteCount == count) return
            favoriteCount = count
            notifyItemChanged(0)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val btnManageFavorites: View = view.findViewById(R.id.btn_manage_favorites)
            val txtFavoritesLabel: TextView = view.findViewById(R.id.txt_favorites_label)
        }

        override fun getItemViewType(position: Int) = VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_section_divider_quickdial, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.txtFavoritesLabel.text = when (favoriteCount) {
                0 -> "Favoritter"
                1 -> "Favoritter · Du har 1 favorittkontakt"
                else -> "Favoritter · Du har $favoriteCount favorittkontakter"
            }
            holder.btnManageFavorites.isEnabled = buttonEnabled
            holder.btnManageFavorites.isFocusable = true
            holder.btnManageFavorites.alpha = if (buttonEnabled) 1f else 0.4f
            holder.btnManageFavorites.setOnClickListener {
                if (buttonEnabled) onManageFavoritesClick()
            }
            holder.btnManageFavorites.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { onNavigateUp(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { onNavigateDown(); true }
                    else -> false
                }
            }
        }

        override fun getItemCount() = 1
    }

    private class SkeletonContactAdapter(
        private val maxCount: Int,
        private val cardType: ContactGridAdapter.CardType
    ) : RecyclerView.Adapter<SkeletonContactAdapter.ViewHolder>() {

        companion object {
            const val VIEW_TYPE_SKELETON_ONLINE = 300
            const val VIEW_TYPE_SKELETON_FAV = 301
        }

        private var count = 0

        fun setVisible(itemCount: Int) {
            if (count == itemCount) return
            val old = count
            count = itemCount
            if (old == 0) notifyItemRangeInserted(0, count)
            else if (count == 0) notifyItemRangeRemoved(0, old)
        }

        override fun getItemCount() = count

        override fun getItemViewType(position: Int) =
            if (cardType == ContactGridAdapter.CardType.QUICK_DIAL) VIEW_TYPE_SKELETON_ONLINE else VIEW_TYPE_SKELETON_FAV

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val res = parent.context.resources
            val density = res.displayMetrics.density
            val avatarSize = res.getDimensionPixelSize(R.dimen.avatar_size_contact_card)
            val nameHeight = (res.getDimension(R.dimen.text_contact_card_name) * 1.3f).toInt()
            val subtitleHeight = (res.getDimension(R.dimen.text_contact_card_subtitle) * 1.3f).toInt()

            val cardLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = (4 * density).toInt()
                    setMargins(m, m, m, m)
                }
                setBackgroundResource(R.drawable.skeleton_card)
                gravity = Gravity.CENTER_HORIZONTAL
                val p = (16 * density).toInt()
                setPadding(p, p, p, p)
            }

            val avatarPlaceholder = View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (8 * density).toInt()
                }
                setBackgroundResource(R.drawable.skeleton_circle)
            }
            cardLayout.addView(avatarPlaceholder)

            val namePlaceholder = View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (80 * density).toInt(), nameHeight
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (4 * density).toInt()
                }
                setBackgroundResource(R.drawable.skeleton_rect)
            }
            cardLayout.addView(namePlaceholder)

            val subtitlePlaceholder = View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (50 * density).toInt(), subtitleHeight
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setBackgroundResource(R.drawable.skeleton_rect)
            }
            cardLayout.addView(subtitlePlaceholder)

            return ViewHolder(cardLayout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.startPulse()
        }

        override fun onViewDetachedFromWindow(holder: ViewHolder) {
            holder.stopPulse()
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.stopPulse()
        }

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

            fun stopPulse() {
                animator?.cancel()
                animator = null
                itemView.alpha = 1f
            }
        }
    }

    private fun navigateToContactDetail(contact: ContactInfo) {
        val detailFragment = ContactDetailFragment.newInstance(
            contactId = contact.id,
            contactName = contact.username,
            contactUserId = contact.contactId.toString(),
            contactEmail = contact.email,
            isInContacts = true,
            isFavorite = contact.isFavorite,
            nickname = contact.nickname,
            avatarUrl = contact.avatarUrl
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, detailFragment)
            .addToBackStack(null)
            .commit()
    }
}
