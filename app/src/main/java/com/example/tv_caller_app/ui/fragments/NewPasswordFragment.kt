package com.example.tv_caller_app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel

class NewPasswordFragment : Fragment() {

    private val viewModel: AuthViewModel by activityViewModels()

    private lateinit var newPasswordInput: EditText
    private lateinit var confirmPasswordInput: EditText
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_new_password, container, false)

        newPasswordInput = view.findViewById(R.id.new_password_input)
        confirmPasswordInput = view.findViewById(R.id.confirm_new_password_input)
        btnSave = view.findViewById(R.id.btn_save_password)
        btnCancel = view.findViewById(R.id.btn_new_pass_cancel)

        setupClickListeners()
        observeViewModel()

        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_new_password))
        return view
    }

    private fun setupClickListeners() {
        btnSave.setOnClickListener {
            val newPass = newPasswordInput.text.toString()
            val confirmPass = confirmPasswordInput.text.toString()

            if (newPass != confirmPass) {
                Toast.makeText(requireContext(), getString(R.string.new_password_mismatch), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPass.isNotBlank()) {
                viewModel.updatePassword(newPass)
            }
        }

        btnCancel.setOnClickListener {
            viewModel.clearPendingResetEmail()
            requireActivity().supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            btnSave.isEnabled = !isLoading
            btnCancel.isEnabled = !isLoading
            newPasswordInput.isEnabled = !isLoading
            confirmPasswordInput.isEnabled = !isLoading
        }

        viewModel.passwordUpdated.observe(viewLifecycleOwner) { updated ->
            updated ?: return@observe
            viewModel.clearPasswordUpdated()
            if (updated) {
                viewModel.clearPendingResetEmail()
                Toast.makeText(requireContext(), getString(R.string.new_password_success), Toast.LENGTH_LONG).show()
                requireActivity().supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }
}
