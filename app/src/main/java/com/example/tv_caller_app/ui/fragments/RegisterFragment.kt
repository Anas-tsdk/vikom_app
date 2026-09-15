package com.example.tv_caller_app.ui.fragments

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
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel

class RegisterFragment : Fragment() {

    private val viewModel: AuthViewModel by activityViewModels()

    companion object {
        private const val TAG = "RegisterFragment"
    }

    private lateinit var usernameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var phoneNumberInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmPasswordInput: EditText
    private lateinit var registerButton: Button
    private lateinit var backToLoginButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_register, container, false)

        usernameInput = view.findViewById(R.id.username_input)
        emailInput = view.findViewById(R.id.email_input)
        phoneNumberInput = view.findViewById(R.id.phone_number_input)
        passwordInput = view.findViewById(R.id.password_input)
        confirmPasswordInput = view.findViewById(R.id.confirm_password_input)
        registerButton = view.findViewById(R.id.register_button)
        backToLoginButton = view.findViewById(R.id.back_to_login_button)

        setupTextWatchers()
        setupClickListeners()
        observeViewModel()
        validateForm()

        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_register))
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

        usernameInput.addTextChangedListener(textWatcher)
        emailInput.addTextChangedListener(textWatcher)
        phoneNumberInput.addTextChangedListener(textWatcher)
        passwordInput.addTextChangedListener(textWatcher)
        confirmPasswordInput.addTextChangedListener(textWatcher)
    }

    private fun validateForm() {
        val username = usernameInput.text.toString()
        val email = emailInput.text.toString()
        val phoneNumber = phoneNumberInput.text.toString()
        val password = passwordInput.text.toString()
        val confirmPassword = confirmPasswordInput.text.toString()

        val isUsernameValid = username.isNotBlank()
        val isEmailValid = email.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
        val isPhoneValid = phoneNumber.isBlank() || phoneNumber.length >= 10
        val isPasswordValid = validatePassword(password)
        val doPasswordsMatch = password == confirmPassword && password.isNotBlank()

        if (phoneNumber.isNotEmpty() && !isPhoneValid) {
            phoneNumberInput.error = getString(R.string.register_phone_error)
        } else {
            phoneNumberInput.error = null
        }

        if (confirmPassword.isNotEmpty() && password.isNotEmpty() && !doPasswordsMatch) {
            confirmPasswordInput.error = getString(R.string.register_password_mismatch_error)
        } else {
            confirmPasswordInput.error = null
        }

        if (password.isNotEmpty() && !isPasswordValid) {
            passwordInput.error = getString(R.string.register_password_requirements_error)
        } else {
            passwordInput.error = null
        }

        registerButton.isEnabled = isUsernameValid && isEmailValid && isPhoneValid && isPasswordValid && doPasswordsMatch
    }

    private fun validatePassword(password: String): Boolean {
        if (password.length < 8) return false

        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }

        return hasUpperCase && hasLowerCase && hasDigit && hasSpecial
    }

    private fun setupClickListeners() {
        registerButton.setOnClickListener {
            val username = usernameInput.text.toString()
            val email = emailInput.text.toString()
            val phoneNumber = phoneNumberInput.text.toString().ifBlank { null }
            val password = passwordInput.text.toString()
            val confirmPassword = confirmPasswordInput.text.toString()

            viewModel.register(
                email,
                password,
                confirmPassword,
                username,
                phoneNumber
            )
        }

        backToLoginButton.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
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
                confirmPasswordInput.error = it
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
            backToLoginButton.isEnabled = !isLoading
            usernameInput.isEnabled = !isLoading
            emailInput.isEnabled = !isLoading
            phoneNumberInput.isEnabled = !isLoading
            passwordInput.isEnabled = !isLoading
            confirmPasswordInput.isEnabled = !isLoading
            if (isLoading) {
                registerButton.isEnabled = false
                registerButton.text = getString(R.string.creating_account)
            } else {
                validateForm()
                registerButton.text = getString(R.string.register_button)
            }
        }

        viewModel.emailVerificationRequired.observe(viewLifecycleOwner) { email ->
            email?.let {
                Log.d(TAG, "Registration successful - navigating to email verification screen for: $email")
                viewModel.resetEmailVerification()

                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.auth_fragment_container, EmailVerificationFragment.newInstance(email))
                    .addToBackStack(null)
                    .commit()
            }
        }

        viewModel.authSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                Log.d(TAG, "Auth successful, navigating to MainActivity")
                Toast.makeText(requireContext(), getString(R.string.login_success), Toast.LENGTH_SHORT).show()

                val intent = Intent(requireContext(), MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)

                viewModel.resetAuthSuccess()
            }
        }
    }
}
