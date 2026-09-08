package com.example.tv_caller_app.ui.fragments

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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
import com.example.tv_caller_app.ui.activities.WelcomeActivity
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel

class LoginFragment : Fragment() {

    private val viewModel: AuthViewModel by activityViewModels()

    companion object {
        private const val TAG = "LoginFragment"
    }

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var forgotPasswordLink: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_login, container, false)

        emailInput = view.findViewById(R.id.email_input)
        passwordInput = view.findViewById(R.id.password_input)
        loginButton = view.findViewById(R.id.login_button)
        registerButton = view.findViewById(R.id.register_button)
        forgotPasswordLink = view.findViewById(R.id.forgot_password_link)

        setupTextWatchers()
        setupClickListeners()
        observeViewModel()
        validateForm()

        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_login))
        return view
    }

    private fun setupTextWatchers() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateForm()
            }
        }

        emailInput.addTextChangedListener(textWatcher)
        passwordInput.addTextChangedListener(textWatcher)
    }

    private fun validateForm() {
        val email = emailInput.text.toString()
        val password = passwordInput.text.toString()

        val isEmailValid = email.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
        val isPasswordValid = password.isNotBlank()

        loginButton.isEnabled = isEmailValid && isPasswordValid
    }

    private fun setupClickListeners() {
        loginButton.setOnClickListener {
            val email = emailInput.text.toString()
            val password = passwordInput.text.toString()
            viewModel.login(email, password)
        }

        registerButton.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.auth_fragment_container, RegisterFragment())
                .addToBackStack(null)
                .commit()
        }

        forgotPasswordLink.setOnClickListener {
            showResetPasswordDialog()
        }
    }

    private fun showResetPasswordDialog() {
        val emailPrefill = emailInput.text.toString().trim()

        val dialogInput = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = getString(R.string.email_hint)
            setText(emailPrefill)
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.reset_password_dialog_title))
            .setMessage(getString(R.string.reset_password_dialog_message))
            .setView(dialogInput)
            .setPositiveButton(getString(R.string.reset_password_send)) { _, _ ->
                val email = dialogInput.text.toString().trim()
                if (email.isNotBlank()) {
                    viewModel.setPendingResetEmail(email)
                    viewModel.resetPassword(email)
                }
            }
            .setNegativeButton(getString(R.string.reset_password_cancel), null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.emailError.observe(viewLifecycleOwner) { error ->
            error?.let {
                emailInput.error = it
                viewModel.clearEmailError()
            }
        }

        viewModel.passwordError.observe(viewLifecycleOwner) { error ->
            error?.let {
                passwordInput.error = it
                viewModel.clearPasswordError()
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Log.w(TAG, "Error: $it")
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            registerButton.isEnabled = !isLoading
            emailInput.isEnabled = !isLoading
            passwordInput.isEnabled = !isLoading

            if (isLoading) {
                loginButton.isEnabled = false
                loginButton.text = getString(R.string.login_button)
            } else {
                validateForm()
                loginButton.text = getString(R.string.login_button)
            }
        }

        viewModel.resetPasswordResult.observe(viewLifecycleOwner) { result ->
            result ?: return@observe
            viewModel.clearResetPasswordResult()
            if (result) {
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.auth_fragment_container, ResetPasswordOtpFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }

        viewModel.authSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                Log.d(TAG, "Login successful, navigating to splash screen")

                val app = requireActivity().application as com.example.tv_caller_app.TVCallerApplication
                app.startSessionRefresh()

                val intent = Intent(requireContext(), WelcomeActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)

                viewModel.resetAuthSuccess()
            }
        }
    }
}
