package com.example.tv_caller_app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel

class ResetPasswordOtpFragment : Fragment() {

    private val viewModel: AuthViewModel by activityViewModels()

    private lateinit var txtInstruction: TextView
    private lateinit var otpInput: EditText
    private lateinit var btnVerify: Button
    private lateinit var btnBack: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reset_password_otp, container, false)

        txtInstruction = view.findViewById(R.id.reset_otp_instruction)
        otpInput = view.findViewById(R.id.otp_code_input)
        btnVerify = view.findViewById(R.id.btn_verify_code)
        btnBack = view.findViewById(R.id.btn_otp_back)

        viewModel.pendingResetEmail.value?.let { email ->
            txtInstruction.text = getString(R.string.reset_otp_instruction, email)
        }

        setupClickListeners()
        observeViewModel()

        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_reset_otp))
        return view
    }

    private fun setupClickListeners() {
        btnVerify.setOnClickListener {
            val email = viewModel.pendingResetEmail.value ?: return@setOnClickListener
            val code = otpInput.text.toString().trim()
            if (code.isNotBlank()) {
                viewModel.verifyRecoveryCode(email, code)
            }
        }

        btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            btnVerify.isEnabled = !isLoading
            btnBack.isEnabled = !isLoading
            otpInput.isEnabled = !isLoading
        }

        viewModel.recoveryVerified.observe(viewLifecycleOwner) { verified ->
            verified ?: return@observe
            viewModel.clearRecoveryVerified()
            if (verified) {
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.auth_fragment_container, NewPasswordFragment())
                    .addToBackStack(null)
                    .commit()
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
