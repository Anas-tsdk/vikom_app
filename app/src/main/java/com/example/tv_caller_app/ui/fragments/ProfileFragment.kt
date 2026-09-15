package com.example.tv_caller_app.ui.fragments

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.ByteArrayOutputStream
import androidx.fragment.app.activityViewModels
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.util.AvatarHelper
import com.example.tv_caller_app.ui.viewmodel.ProfileViewModel
import androidx.core.graphics.scale
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat

class ProfileFragment : Fragment() {

    private val viewModel: ProfileViewModel by activityViewModels()
    private lateinit var imgAvatar: ImageView
    private lateinit var btnChangePhoto: TextView
    private lateinit var txtUsername: TextView
    private lateinit var btnGoBack: TextView

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val rawBytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (rawBytes == null || rawBytes.isEmpty()) {
                Toast.makeText(requireContext(), "Could not read image", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            if (bitmap == null) {
                Toast.makeText(requireContext(), "Image format not supported. Use JPEG or PNG.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val maxDim = 512
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                bitmap.scale((bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt())
            } else bitmap

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            viewModel.uploadAvatar(out.toByteArray())
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_profile))

        (activity as? MainActivity)?.hideHeader()

        imgAvatar = view.findViewById(R.id.img_avatar)
        btnChangePhoto = view.findViewById(R.id.btn_change_photo)
        txtUsername = view.findViewById(R.id.txt_username)
        btnGoBack = view.findViewById(R.id.btn_go_back)

        setupClickListeners()
        observeViewModel()

        viewModel.loadProfile()

        txtUsername.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.showHeader()
    }

    private fun setupClickListeners() {
        btnChangePhoto.setOnClickListener {
            showAvatarOptions()
        }

        txtUsername.setOnClickListener {
            showUsernameDialog()
        }

        btnGoBack.setOnClickListener {
            (activity as? MainActivity)?.showHome()
        }
    }

    private fun showAvatarOptions() {
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION
        if (isTv) {
            showAvatarSelectionDialog()
        } else {
            val hasAvatar = viewModel.avatarUrl.value != null
            val options = buildList {
                add(getString(R.string.choose_avatar))
                add(getString(R.string.upload_photo))
                if (hasAvatar) add(getString(R.string.remove_photo))
            }.toTypedArray()

            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.change_photo))
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showAvatarSelectionDialog()
                        1 -> imagePickerLauncher.launch("image/*")
                        2 -> viewModel.removeAvatar()
                    }
                }
                .show()
        }
    }

    private fun showAvatarSelectionDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_avatar_selection)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setCancelable(true)

        val avatarClickListener = View.OnClickListener { v ->
            val avatarName = when (v.id) {
                R.id.avatar_blue -> "blue"
                R.id.avatar_green -> "green"
                R.id.avatar_red -> "red"
                R.id.avatar_purple -> "purple"
                R.id.avatar_orange -> "orange"
                R.id.avatar_teal -> "teal"
                else -> return@OnClickListener
            }
            dialog.dismiss()
            viewModel.setPresetAvatar(avatarName)
        }

        dialog.findViewById<ImageView>(R.id.avatar_blue).setOnClickListener(avatarClickListener)
        dialog.findViewById<ImageView>(R.id.avatar_green).setOnClickListener(avatarClickListener)
        dialog.findViewById<ImageView>(R.id.avatar_red).setOnClickListener(avatarClickListener)
        dialog.findViewById<ImageView>(R.id.avatar_purple).setOnClickListener(avatarClickListener)
        dialog.findViewById<ImageView>(R.id.avatar_orange).setOnClickListener(avatarClickListener)
        dialog.findViewById<ImageView>(R.id.avatar_teal).setOnClickListener(avatarClickListener)

        val btnRemove = dialog.findViewById<android.widget.Button>(R.id.btn_remove_avatar)
        if (viewModel.avatarUrl.value != null) {
            btnRemove.visibility = View.VISIBLE
            btnRemove.setOnClickListener {
                dialog.dismiss()
                viewModel.removeAvatar()
            }
        } else {
            btnRemove.visibility = View.GONE
        }

        dialog.findViewById<android.widget.Button>(R.id.btn_done_avatar).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.6).toInt()
        val height = (displayMetrics.heightPixels * 0.8).toInt()
        dialog.window?.setLayout(width, height)

        dialog.findViewById<ImageView>(R.id.avatar_blue).requestFocus()
    }

    private fun observeViewModel() {
        viewModel.username.observe(viewLifecycleOwner) { username ->
            txtUsername.text = username
        }

        viewModel.avatarUrl.observe(viewLifecycleOwner) { url ->
            loadAvatar(url)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            txtUsername.isEnabled = !isLoading
            btnChangePhoto.isEnabled = !isLoading
            txtUsername.alpha = if (isLoading) 0.5f else 1.0f
            btnChangePhoto.alpha = if (isLoading) 0.5f else 1.0f
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        viewModel.successMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.clearSuccess()
            }
        }
    }

    private fun loadAvatar(url: String?) {
        if (!isAdded) return
        AvatarHelper.loadAvatar(imgAvatar, url, R.drawable.call_avatar_circle)
    }

    private fun showUsernameDialog() {
        val maxUsernameLength = 20
        val editText = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.username_hint)
            setText(viewModel.username.value ?: "")
            setPadding(48, 32, 48, 32)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxUsernameLength))
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.username_label))
            .setView(editText)
            .setPositiveButton(getString(R.string.save)) { dialog, _ ->
                val newUsername = editText.text.toString().trim()
                if (newUsername.isNotBlank() && newUsername.length <= maxUsernameLength) {
                    viewModel.updateUsername(newUsername)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

}
