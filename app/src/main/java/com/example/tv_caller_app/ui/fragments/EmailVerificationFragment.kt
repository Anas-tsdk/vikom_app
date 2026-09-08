package com.example.tv_caller_app.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel

class EmailVerificationFragment : Fragment() {

    companion object {
        private const val TAG = "EmailVerificationFrag"
        private const val ARG_EMAIL = "email"

        fun newInstance(email: String): EmailVerificationFragment {
            val fragment = EmailVerificationFragment()
            fragment.arguments = Bundle().apply { putString(ARG_EMAIL, email) }
            return fragment
        }
    }

    private val viewModel: AuthViewModel by activityViewModels()

    private lateinit var txtMessage: TextView
    private lateinit var btnResend: Button
    private lateinit var btnBackToLogin: Button

    private var email: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_email_verification, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_email_verification))

        email = arguments?.getString(ARG_EMAIL) ?: ""

        txtMessage = view.findViewById(R.id.txt_verification_message)
        btnResend = view.findViewById(R.id.btn_resend_email)
        btnBackToLogin = view.findViewById(R.id.btn_back_to_login)

        txtMessage.text = getString(R.string.email_verification_message, email)

        btnResend.setOnClickListener {
            Log.d(TAG, "Resend email requested for: $email")
            viewModel.resendVerificationEmail(email)
        }

        btnBackToLogin.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        observeViewModel()

        btnResend.requestFocus()
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            btnResend.isEnabled = !isLoading
            btnBackToLogin.isEnabled = !isLoading
            btnResend.text = if (isLoading) getString(R.string.resend_email_sending)
                             else getString(R.string.resend_email_button)
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }
}
