package com.example.tv_caller_app.ui.util

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.example.tv_caller_app.settings.SettingsManager
import java.util.Locale

/** ViewModels use the chosen app language even when the application context has another locale. */
fun Context.localizedString(@StringRes id: Int): String {
    val configuration = Configuration(resources.configuration).apply {
        setLocale(Locale.forLanguageTag(SettingsManager.getInstance(this@localizedString).language))
    }
    return createConfigurationContext(configuration).getString(id)
}
