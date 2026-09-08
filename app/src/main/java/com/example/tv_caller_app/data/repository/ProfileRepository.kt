package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.data.datasource.ProfileDataSource
import com.example.tv_caller_app.domain.UsernameNotUniqueException
import com.example.tv_caller_app.domain.UserProfile
import com.example.tv_caller_app.domain.usecase.UpdateAvatarUseCase

class ProfileRepository private constructor(
    private val dataSource: ProfileDataSource,
    private val updateAvatarUseCase: UpdateAvatarUseCase
) {

    companion object {
        private const val TAG = "ProfileRepository"

        @Volatile
        private var instance: ProfileRepository? = null

        fun getInstance(dataSource: ProfileDataSource, updateAvatarUseCase: UpdateAvatarUseCase): ProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: ProfileRepository(dataSource, updateAvatarUseCase).also { instance = it }
            }
        }
    }

    suspend fun getProfile(): UserProfile? = try {
        dataSource.getProfile()?.let { p -> UserProfile(p.username, p.email, p.contactId, p.avatarUrl) }
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching profile", e)
        null
    }

    fun getStoredEmail() = dataSource.getStoredEmail()

    suspend fun updateUsername(username: String): Result<Unit> {
        return try {
            val taken = dataSource.isUsernameTaken(username)
            if (taken) {
                return Result.failure(UsernameNotUniqueException(username))
            }
            Log.d(TAG, "Updating username to '$username'")
            dataSource.updateUsername(username)
            Log.d(TAG, "Username updated successfully")
            Result.success(Unit)
        } catch (e: UsernameNotUniqueException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating username", e)
            val isDbUniqueViolation = e.message?.contains("unique", ignoreCase = true) == true ||
                e.message?.contains("duplicate", ignoreCase = true) == true ||
                e.message?.contains("profiles_username_key", ignoreCase = true) == true
            if (isDbUniqueViolation) {
                Result.failure(UsernameNotUniqueException(username))
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun uploadAvatar(imageBytes: ByteArray): String? = updateAvatarUseCase.upload(imageBytes)

    suspend fun setPresetAvatar(avatarName: String): Boolean {
        return try {
            dataSource.updateAvatarUrl("preset:$avatarName")
            Log.d(TAG, "Preset avatar set: $avatarName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting preset avatar", e)
            false
        }
    }

    suspend fun deleteAvatar(): Boolean = updateAvatarUseCase.delete()

    suspend fun hasSeenOnboarding(): Boolean = try {
        dataSource.getHasSeenOnboarding()
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching onboarding flag", e)
        true 
    }

    suspend fun markOnboardingComplete() = try {
        dataSource.markOnboardingComplete()
    } catch (e: Exception) {
        Log.e(TAG, "Error marking onboarding complete", e)
    }
}
