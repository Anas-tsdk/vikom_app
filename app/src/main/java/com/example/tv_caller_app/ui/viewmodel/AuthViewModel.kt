package com.example.tv_caller_app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.example.tv_caller_app.data.repository.AuthRepository

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    companion object {
        private const val TAG = "AuthViewModel"

        fun factory(authRepository: AuthRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AuthViewModel(authRepository) as T
        }
    }

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _authSuccess = MutableLiveData(false)
    val authSuccess: LiveData<Boolean> = _authSuccess

    private val _loggedOut = MutableLiveData(false)
    val loggedOut: LiveData<Boolean> = _loggedOut

    private val _sessionValid = MutableLiveData<Boolean?>(null)
    val sessionValid: LiveData<Boolean?> = _sessionValid

    private val _cachedUsername = MutableLiveData<String?>()
    val cachedUsername: LiveData<String?> = _cachedUsername

    private val _emailVerificationRequired = MutableLiveData<String?>() 
    val emailVerificationRequired: LiveData<String?> = _emailVerificationRequired

    private val _resetPasswordResult = MutableLiveData<Boolean?>()
    val resetPasswordResult: LiveData<Boolean?> = _resetPasswordResult

    private val _pendingResetEmail = MutableLiveData<String?>()
    val pendingResetEmail: LiveData<String?> = _pendingResetEmail

    private val _recoveryVerified = MutableLiveData<Boolean?>()
    val recoveryVerified: LiveData<Boolean?> = _recoveryVerified

    private val _passwordUpdated = MutableLiveData<Boolean?>()
    val passwordUpdated: LiveData<Boolean?> = _passwordUpdated

    private val _emailError = MutableLiveData<String?>()
    val emailError: LiveData<String?> = _emailError

    private val _passwordError = MutableLiveData<String?>()
    val passwordError: LiveData<String?> = _passwordError

    fun checkSession() {
        viewModelScope.launch {
            try {
                val isValid = authRepository.isSessionValid()
                if (isValid) {
                    _cachedUsername.value = authRepository.getUsername()
                }
                _sessionValid.value = isValid
            } catch (e: Exception) {
                Log.e(TAG, "Error checking session", e)
                _sessionValid.value = false
            }
        }
    }

    fun login(email: String, password: String) {
        Log.d(TAG, "login() called with email: $email")

        _emailError.value = null
        _passwordError.value = null
        _errorMessage.value = null
        _emailVerificationRequired.value = null

        if (!validateEmail(email)) {
            _emailError.value = "Please enter a valid email"
            return
        }

        if (password.isBlank()) {
            _passwordError.value = "Please enter your password"
            return
        }

        _isLoading.value = true

        viewModelScope.launch {
            try {
                val result = authRepository.signIn(email, password)

                if (result.isSuccess) {
                    Log.d(TAG, "Login successful")
                    _authSuccess.value = true
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Login failed"
                    Log.e(TAG, "Login failed: $error")
                    _errorMessage.value = error
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                _errorMessage.value = e.message ?: "Login failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun register(email: String, password: String, confirmPassword: String, username: String? = null, phoneNumber: String? = null) {
        Log.d(TAG, "register() called with email: $email, username: $username, phoneNumber: $phoneNumber")

        _emailError.value = null
        _passwordError.value = null
        _errorMessage.value = null
        _emailVerificationRequired.value = null

        if (!validateEmail(email)) {
            _emailError.value = "Please enter a valid email"
            return
        }

        if (!validatePassword(password)) {
            _passwordError.value = getPasswordRequirements()
            return
        }

        if (password != confirmPassword) {
            _passwordError.value = "Passwords do not match"
            return
        }

        _isLoading.value = true

        viewModelScope.launch {
            try {
                val result = authRepository.signUp(email, password, username, phoneNumber)

                if (result.isSuccess) {
                    Log.d(TAG, "Registration successful - verification email sent")
                    _emailVerificationRequired.value = email
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Registration failed"
                    Log.e(TAG, "Registration failed: $error")
                    _errorMessage.value = error
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration error", e)
                _errorMessage.value = e.message ?: "Registration failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun validateEmail(email: String): Boolean {
        if (email.isBlank()) return false
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun validatePassword(password: String): Boolean {
        if (password.length < 8) return false

        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }

        return hasUpperCase && hasLowerCase && hasDigit && hasSpecial
    }

    private fun getPasswordRequirements(): String {
        return "Password must contain:\n" +
                "• At least 8 characters\n" +
                "• One uppercase letter\n" +
                "• One lowercase letter\n" +
                "• One number\n" +
                "• One special character"
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearEmailError() {
        _emailError.value = null
    }

    fun clearPasswordError() {
        _passwordError.value = null
    }

    fun resetAuthSuccess() {
        _authSuccess.value = false
    }

    fun resendVerificationEmail(email: String) {
        Log.d(TAG, "Resending verification email to: $email")
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val result = authRepository.resendVerificationEmail(email)

                if (result.isSuccess) {
                    Log.d(TAG, "Verification email resent successfully")
                    _errorMessage.value = "Verification email sent! Check your inbox."
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Failed to send email"
                    Log.e(TAG, "Failed to resend verification email: $error")
                    _errorMessage.value = error
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resending verification email", e)
                _errorMessage.value = e.message ?: "Failed to send email"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetEmailVerification() {
        _emailVerificationRequired.value = null
    }

    fun resetPassword(email: String) {
        Log.d(TAG, "resetPassword() called for: $email")
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val result = authRepository.resetPassword(email)
                if (result.isSuccess) {
                    _resetPasswordResult.value = true
                } else {
                    val msg = result.exceptionOrNull()?.message
                    Log.e(TAG, "Reset password failed: $msg")
                    _errorMessage.value = msg ?: "Kunne ikke sende kode"
                    _resetPasswordResult.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reset password error", e)
                _errorMessage.value = e.message ?: "Kunne ikke sende kode"
                _resetPasswordResult.value = false
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearResetPasswordResult() {
        _resetPasswordResult.value = null
    }

    fun setPendingResetEmail(email: String) {
        _pendingResetEmail.value = email
    }

    fun clearPendingResetEmail() {
        _pendingResetEmail.value = null
    }

    fun verifyRecoveryCode(email: String, code: String) {
        Log.d(TAG, "verifyRecoveryCode() for: $email")
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val result = authRepository.verifyRecoveryOtp(email, code)
                if (result.isSuccess) {
                    _recoveryVerified.value = true
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Ugyldig kode"
                    _recoveryVerified.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyRecoveryCode error", e)
                _errorMessage.value = e.message ?: "Ugyldig kode"
                _recoveryVerified.value = false
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearRecoveryVerified() {
        _recoveryVerified.value = null
    }

    fun updatePassword(newPassword: String) {
        Log.d(TAG, "updatePassword() called")
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val result = authRepository.updatePassword(newPassword)
                if (result.isSuccess) {
                    _passwordUpdated.value = true
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Kunne ikke oppdatere passord"
                    _passwordUpdated.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "updatePassword error", e)
                _errorMessage.value = e.message ?: "Kunne ikke oppdatere passord"
                _passwordUpdated.value = false
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearPasswordUpdated() {
        _passwordUpdated.value = null
    }

    fun logout() {
        Log.d(TAG, "Logout requested")
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val result = authRepository.signOut()

                if (result.isSuccess) {
                    Log.d(TAG, "Logout successful")
                    _loggedOut.value = true
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Logout failed"
                    Log.e(TAG, "Logout failed: $error")
                    _errorMessage.value = error
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logout error", e)
                _errorMessage.value = e.message ?: "Logout failed"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
