package com.example.tv_caller_app.domain.usecase

import android.util.Log
import com.example.tv_caller_app.data.datasource.AvatarDataSource
import com.example.tv_caller_app.data.datasource.ProfileDataSource

class UpdateAvatarUseCase(
    private val avatarDataSource: AvatarDataSource,
    private val profileDataSource: ProfileDataSource
) {

    companion object {
        private const val TAG = "UpdateAvatarUseCase"
    }

    suspend fun upload(imageBytes: ByteArray): String? {
        return try {
            Log.d(TAG, "Uploading avatar (${imageBytes.size} bytes)")
            val publicUrl = avatarDataSource.uploadFile(imageBytes)
            Log.d(TAG, "Avatar uploaded, public URL: $publicUrl")
            profileDataSource.updateAvatarUrl(publicUrl)
            "$publicUrl?v=${System.currentTimeMillis()}"
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading avatar", e)
            null
        }
    }

    suspend fun delete(): Boolean {
        return try {
            try {
                avatarDataSource.deleteFile()
            } catch (_: Exception) {
                Log.w(TAG, "No storage file to delete (might be preset avatar)")
            }
            profileDataSource.updateAvatarUrl(null)
            Log.d(TAG, "Avatar deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting avatar", e)
            false
        }
    }
}
