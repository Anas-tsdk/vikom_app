package com.example.tv_caller_app.ui.fragments

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import android.widget.ImageView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.util.AvatarHelper
import com.example.tv_caller_app.ui.viewmodel.ContactDetailViewModel

class ContactDetailFragment : Fragment() {

    private val viewModel: ContactDetailViewModel by activityViewModels()
    private lateinit var contactName: TextView
    private lateinit var contactOnlineStatus: TextView
    private lateinit var btnGoBack: TextView
    private lateinit var btnCall: View
    private lateinit var btnEditContact: TextView
    private lateinit var btnAddContact: TextView
    private lateinit var contactNickname: TextView
    private lateinit var btnViewMessages: View
    private lateinit var btnVideoCall: View

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        val denied = permissions.filter { !it.value }.keys.toList()
        viewModel.onPermissionsResult(allGranted, denied)
    }

    private var isContactOnline: Boolean = false

    companion object {
        private const val TAG = "ContactDetailFragment"
        private const val ARG_CONTACT_ID = "contact_id"
        private const val ARG_CONTACT_NAME = "contact_name"
        private const val ARG_CONTACT_USER_ID = "contact_user_id"
        private const val ARG_CONTACT_EMAIL = "contact_email"
        private const val ARG_IS_IN_CONTACTS = "is_in_contacts"
        private const val ARG_IS_FAVORITE = "is_favorite"
        private const val ARG_NICKNAME = "nickname"
        private const val ARG_AVATAR_URL = "avatar_url"

        fun newInstance(
            contactId: String,
            contactName: String,
            contactUserId: String,
            contactEmail: String? = null,
            isInContacts: Boolean = true,
            isFavorite: Boolean = false,
            nickname: String? = null,
            avatarUrl: String? = null
        ): ContactDetailFragment {
            val fragment = ContactDetailFragment()
            val args = Bundle()
            args.putString(ARG_CONTACT_ID, contactId)
            args.putString(ARG_CONTACT_NAME, contactName)
            args.putString(ARG_CONTACT_USER_ID, contactUserId)
            args.putString(ARG_CONTACT_EMAIL, contactEmail)
            args.putBoolean(ARG_IS_IN_CONTACTS, isInContacts)
            args.putBoolean(ARG_IS_FAVORITE, isFavorite)
            args.putString(ARG_NICKNAME, nickname)
            args.putString(ARG_AVATAR_URL, avatarUrl)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_contact_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.hideHeader()

        contactName = view.findViewById(R.id.contact_name)
        contactOnlineStatus = view.findViewById(R.id.contact_online_status)
        btnGoBack = view.findViewById(R.id.btn_go_back)
        btnCall = view.findViewById(R.id.btn_call)
        btnEditContact = view.findViewById(R.id.btn_edit_contact)
        btnAddContact = view.findViewById(R.id.btn_add_contact)
        contactNickname = view.findViewById(R.id.contact_nickname)
        btnViewMessages = view.findViewById(R.id.btn_view_messages)
        btnVideoCall = view.findViewById(R.id.btn_video_call)

        val id = arguments?.getString(ARG_CONTACT_ID) ?: ""
        val name = arguments?.getString(ARG_CONTACT_NAME) ?: ""
        val userIdStr = arguments?.getString(ARG_CONTACT_USER_ID) ?: ""
        val contactIdInt = userIdStr.toIntOrNull() ?: 0
        val email = arguments?.getString(ARG_CONTACT_EMAIL)
        val isInContacts = arguments?.getBoolean(ARG_IS_IN_CONTACTS, true) ?: true
        val isFavorite = arguments?.getBoolean(ARG_IS_FAVORITE, false) ?: false
        val nickname = arguments?.getString(ARG_NICKNAME)
        val avatarUrl = arguments?.getString(ARG_AVATAR_URL)

        viewModel.setContactDetails(id, name, contactIdInt, email, isFavorite, nickname, avatarUrl)

        val imgAvatar = view.findViewById<ImageView>(R.id.img_contact_avatar)
        AvatarHelper.loadAvatar(imgAvatar, avatarUrl, R.drawable.call_avatar_circle)

        if (isInContacts) {
            btnEditContact.visibility = View.VISIBLE
            btnAddContact.visibility = View.GONE
            btnViewMessages.visibility = View.VISIBLE
        } else {
            btnEditContact.visibility = View.GONE
            btnAddContact.visibility = View.VISIBLE
            btnViewMessages.visibility = View.GONE
        }

        viewModel.checkOnlineStatus(contactIdInt)

        observeViewModel()
        setupClickListeners()

        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_contact_detail))
        btnCall.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.showHeader()
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        viewModel.hasCameraHardware.observe(viewLifecycleOwner) { hasCamera ->
            if (!hasCamera) {
                btnVideoCall.isEnabled = false
                btnVideoCall.alpha = 0.4f
            }
        }

        viewModel.requestPermissionsEvent.observe(viewLifecycleOwner) { permissions ->
            if (permissions != null) {
                permissionLauncher.launch(permissions)
                viewModel.consumePermissionsEvent()
            }
        }

        viewModel.callReadyEvent.observe(viewLifecycleOwner) { params ->
            if (params != null) {
                val callViewModel = (requireActivity().application as TVCallerApplication).getCallViewModel()
                if (callViewModel != null) {
                    callViewModel.initiateCall(params.profileId, params.name, params.contactId, params.nickname, params.avatarUrl, params.withVideo)
                } else {
                    Log.w(TAG, "CallViewModel not initialized — cannot start call")
                }
                viewModel.consumeCallReadyEvent()
            }
        }

        viewModel.contactName.observe(viewLifecycleOwner) { name ->
            contactName.text = name
        }

        viewModel.callHistoryCount.observe(viewLifecycleOwner) { count ->
            Log.d(TAG, "Call history updated: $count records")
        }

        viewModel.isCallInProgress.observe(viewLifecycleOwner) { inProgress ->
            if (inProgress) {
                val name = viewModel.contactName.value ?: getString(R.string.unknown_contact)
                val contactId = viewModel.userContactId.value ?: 0
                Toast.makeText(
                    requireContext(),
                    getString(R.string.calling_contact, name, contactId.toString()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


        viewModel.nickname.observe(viewLifecycleOwner) { nickname ->
            if (!nickname.isNullOrBlank()) {
                contactNickname.text = "($nickname)"
                contactNickname.visibility = View.VISIBLE
            } else {
                contactNickname.visibility = View.GONE
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Log.w(TAG, "Error message: $it")
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            Log.d(TAG, "Loading state: $isLoading")
            btnEditContact.isEnabled = !isLoading
            btnAddContact.isEnabled = !isLoading
            btnCall.isEnabled = !isLoading
            btnVideoCall.isEnabled = !isLoading
            btnEditContact.alpha = if (isLoading) 0.5f else 1.0f
            btnAddContact.alpha = if (isLoading) 0.5f else 1.0f
            btnCall.alpha = if (isLoading) 0.5f else 1.0f
            btnVideoCall.alpha = if (isLoading) 0.5f else 1.0f
        }

        viewModel.isDeleted.observe(viewLifecycleOwner) { isDeleted ->
            if (isDeleted) {
                Log.d(TAG, "Contact deleted successfully")
                Toast.makeText(
                    requireContext(),
                    getString(R.string.contact_deleted),
                    Toast.LENGTH_SHORT
                ).show()

                viewModel.resetDeletedState()

                requireActivity().supportFragmentManager.popBackStack()
            }
        }

        viewModel.isAdded.observe(viewLifecycleOwner) { isAdded ->
            if (isAdded) {
                arguments?.apply {
                    putString(ARG_CONTACT_ID, viewModel.contactId.value)
                    putBoolean(ARG_IS_IN_CONTACTS, true)
                    putBoolean(ARG_IS_FAVORITE, viewModel.isFavorite.value == true)
                    putString(ARG_NICKNAME, viewModel.nickname.value)
                }
                Log.d(TAG, "Contact added successfully")
                Toast.makeText(
                    requireContext(),
                    getString(R.string.added_to_contacts),
                    Toast.LENGTH_SHORT
                ).show()

                viewModel.resetAddedState()

                btnAddContact.visibility = View.GONE
                btnCall.visibility = View.VISIBLE
                btnEditContact.visibility = View.VISIBLE
                btnViewMessages.visibility = View.VISIBLE
                btnVideoCall.visibility = View.VISIBLE
            }
        }

        viewModel.presenceOnline.observe(viewLifecycleOwner) { isOnline ->
            val webrtcStatus = viewModel.presenceWebrtcStatus.value ?: "offline"
            isContactOnline = isOnline && webrtcStatus == "available"
            updateOnlineStatusUI(isOnline, webrtcStatus)
        }

        viewModel.presenceWebrtcStatus.observe(viewLifecycleOwner) { webrtcStatus ->
            val isOnline = viewModel.presenceOnline.value ?: false
            isContactOnline = isOnline && webrtcStatus == "available"
            updateOnlineStatusUI(isOnline, webrtcStatus)
        }

    }

    private fun setupClickListeners() {
        btnGoBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        btnCall.setOnClickListener {
            handleCallButtonClick(withVideo = false)
        }

        btnVideoCall.setOnClickListener {
            handleCallButtonClick(withVideo = true)
        }

        btnEditContact.setOnClickListener {
            showEditContactDialog()
        }

        btnAddContact.setOnClickListener {
            showAddContactConfirmationDialog()
        }

        btnViewMessages.setOnClickListener {
            showMessagesDialog()
        }
    }

    private fun showEditContactDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_edit_contact)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setCancelable(true)

        val txtTitle = dialog.findViewById<TextView>(R.id.txt_edit_title)
        val editNickname = dialog.findViewById<android.widget.EditText>(R.id.edit_nickname)
        val itemDelete = dialog.findViewById<TextView>(R.id.item_edit_delete)
        val btnDone = dialog.findViewById<TextView>(R.id.btn_edit_done)
        val btnDialogFavorite = dialog.findViewById<TextView>(R.id.btn_dialog_favorite)
        val btnDialogPriority = dialog.findViewById<TextView>(R.id.btn_dialog_priority)

        txtTitle.text = getString(R.string.edit_contact_title, viewModel.contactName.value ?: "")
        editNickname.setText(viewModel.nickname.value ?: "")

        fun applyFavStyle(isFav: Boolean) {
            btnDialogFavorite.text = if (isFav) getString(R.string.remove_favorite) else getString(R.string.add_favorite)
            btnDialogFavorite.setBackgroundResource(if (isFav) R.drawable.button_primary else R.drawable.button_secondary)
            btnDialogPriority.visibility = if (isFav) View.VISIBLE else View.GONE
        }

        fun applyPrioStyle(isPrio: Boolean, isFull: Boolean) {
            when {
                isPrio -> {
                    btnDialogPriority.setText(R.string.priority_selected)
                    btnDialogPriority.setBackgroundResource(R.drawable.button_primary)
                    btnDialogPriority.isEnabled = true
                    btnDialogPriority.alpha = 1f
                }
                isFull -> {
                    btnDialogPriority.setText(R.string.priority_no_space)
                    btnDialogPriority.setBackgroundResource(R.drawable.button_secondary)
                    btnDialogPriority.isEnabled = false
                    btnDialogPriority.alpha = 0.4f
                }
                else -> {
                    btnDialogPriority.setText(R.string.priority_not_selected)
                    btnDialogPriority.setBackgroundResource(R.drawable.button_secondary)
                    btnDialogPriority.isEnabled = true
                    btnDialogPriority.alpha = 1f
                }
            }
        }

        applyFavStyle(viewModel.isFavorite.value == true)
        applyPrioStyle(viewModel.isPriority.value == true, viewModel.isPriorityFull.value == true)

        val favObserver = androidx.lifecycle.Observer<Boolean> { isFav ->
            applyFavStyle(isFav)
            applyPrioStyle(viewModel.isPriority.value == true, viewModel.isPriorityFull.value == true)
        }
        val prioObserver = androidx.lifecycle.Observer<Boolean> { isPrio ->
            applyPrioStyle(isPrio, viewModel.isPriorityFull.value == true)
        }
        viewModel.isFavorite.observe(viewLifecycleOwner, favObserver)
        viewModel.isPriority.observe(viewLifecycleOwner, prioObserver)
        dialog.setOnDismissListener {
            viewModel.isFavorite.removeObserver(favObserver)
            viewModel.isPriority.removeObserver(prioObserver)
        }

        btnDialogFavorite.setOnClickListener { viewModel.toggleFavorite() }
        btnDialogPriority.setOnClickListener { viewModel.togglePriority() }

        itemDelete.setOnClickListener {
            val name = viewModel.contactName.value ?: getString(R.string.unknown_contact)
            dialog.dismiss()
            (activity as? MainActivity)?.showConfirmDialog(
                title = getString(R.string.delete_contact_title),
                message = getString(R.string.delete_contact_message, name),
                icon = "🗑️",
                onConfirm = { viewModel.deleteContact() }
            )
        }

        btnDone.setOnClickListener {
            viewModel.updateNickname(editNickname.text.toString().trim().ifBlank { null })
            dialog.dismiss()
        }

        dialog.show()

        val displayMetrics = resources.displayMetrics
        dialog.window?.setLayout(
            (displayMetrics.widthPixels * 0.6).toInt(),
            (displayMetrics.heightPixels * 0.8).toInt()
        )

        editNickname.requestFocus()
    }

    private fun showAddContactConfirmationDialog() {
        val name = viewModel.contactName.value ?: getString(R.string.unknown_contact)
        (activity as? MainActivity)?.showConfirmDialog(
            title = getString(R.string.add_contact_title),
            message = getString(R.string.add_contact_message, name),
            icon = "➕",
            onConfirm = {
                Log.d(TAG, "User confirmed adding contact")
                viewModel.addToContacts()
            }
        )
    }

    private fun showMessagesDialog() {
        val name = viewModel.contactName.value ?: getString(R.string.unknown_contact)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_messages)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setCancelable(true)

        dialog.findViewById<TextView>(R.id.txt_messages_title).text = getString(R.string.messages_dialog_title, name)

        val dummyMessages = listOf(
            "Hei! Hvordan har du det?" to "10:30",
            "Kan vi snakkes i morgen?" to "10:32",
            "Bare si fra når du er klar 😊" to "10:45",
            "God natt!" to "22:15"
        )

        val container = dialog.findViewById<LinearLayout>(R.id.messages_container)
        val px16 = (16 * resources.displayMetrics.density).toInt()
        val px8 = (8 * resources.displayMetrics.density).toInt()

        dummyMessages.forEach { (msg, time) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, px8) }
                isFocusable = true
                background = resources.getDrawable(R.drawable.button_dialog_item, null)
                setPadding(px16, px16, px16, px16)
            }

            val msgView = TextView(requireContext()).apply {
                text = msg
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_primary))
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val timeView = TextView(requireContext()).apply {
                text = time
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }

            row.addView(msgView)
            row.addView(timeView)
            container.addView(row)
        }

        val btnClose = dialog.findViewById<TextView>(R.id.btn_messages_close)
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()

        val displayMetrics = resources.displayMetrics
        dialog.window?.setLayout(
            (displayMetrics.widthPixels * 0.6).toInt(),
            (displayMetrics.heightPixels * 0.8).toInt()
        )

        val firstItem = container.getChildAt(0)
        (firstItem ?: btnClose).requestFocus()
    }

    private fun handleCallButtonClick(withVideo: Boolean) {
        if (!isContactOnline) {
            Toast.makeText(
                requireContext(),
                getString(R.string.calling_contact_offline),
                Toast.LENGTH_SHORT
            ).show()
        }
        viewModel.onCallRequested(withVideo)
    }

    private fun updateOnlineStatusUI(isOnline: Boolean, webrtcStatus: String) {
        when {
            !isOnline -> {
                contactOnlineStatus.text = getString(R.string.status_offline)
                contactOnlineStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            }
            webrtcStatus == "available" -> {
                contactOnlineStatus.text = getString(R.string.status_online)
                contactOnlineStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
            }
            webrtcStatus == "in_call" -> {
                contactOnlineStatus.text = getString(R.string.status_in_call)
                contactOnlineStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
            }
            webrtcStatus == "busy" -> {
                contactOnlineStatus.text = getString(R.string.status_busy)
                contactOnlineStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            }
            else -> {
                contactOnlineStatus.text = getString(R.string.status_offline)
                contactOnlineStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            }
        }
    }
}
