package com.example.tv_caller_app.ui.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.settings.SettingsManager
import com.example.tv_caller_app.ui.fragments.LoginFragment
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel
import java.util.Locale

class AuthActivity : FragmentActivity() {

    companion object {
        private const val TAG = "AuthActivity"
    }

    private lateinit var authViewModel: AuthViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLocale()
        setContentView(R.layout.activity_auth)
        com.example.tv_caller_app.ui.util.TvWindowManager.installContentInsets(this)

        val authRepository = (application as TVCallerApplication).getAuthRepository()
        authViewModel = ViewModelProvider(this, AuthViewModel.factory(authRepository))[AuthViewModel::class.java]

        observeViewModel()
        authViewModel.checkSession()
    }

    private fun applyLocale() {
        val lang = SettingsManager.getInstance(this).language
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun observeViewModel() {
        authViewModel.sessionValid.observe(this) { isValid ->
            when (isValid) {
                true -> {
                    Log.d(TAG, "Valid session found, redirecting to MainActivity")
                    navigateToMainActivity()
                }
                false -> {
                    Log.d(TAG, "No valid session, showing login screen")
                    showLoginScreen()
                }
                null -> {} 
            }
        }
    }

    private fun showLoginScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.auth_fragment_container, LoginFragment())
            .commitNow()
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
