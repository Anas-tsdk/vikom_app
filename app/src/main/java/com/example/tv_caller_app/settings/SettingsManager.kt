package com.example.tv_caller_app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context).also { instance = it }
            }
        }

        const val DEFAULT_LANGUAGE = "nb"
    }

    var language: String
        get() = prefs.getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = prefs.edit { putString("language", value) }

    var ringtoneEnabled: Boolean
        get() = prefs.getBoolean("ringtone_enabled", true)
        set(value) = prefs.edit { putBoolean("ringtone_enabled", value) }

    var autoAnswer: Boolean
        get() = prefs.getBoolean("auto_answer", false)
        set(value) = prefs.edit { putBoolean("auto_answer", value) }

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean("vibration_enabled", true)
        set(value) = prefs.edit { putBoolean("vibration_enabled", value) }
}
