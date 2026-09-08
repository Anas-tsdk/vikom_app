package com.example.tv_caller_app.ui.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.tv_caller_app.R
import com.example.tv_caller_app.settings.SettingsManager
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.viewmodel.SettingsViewModel

class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var settings: SettingsManager

    private lateinit var txtLanguageValue: TextView
    private lateinit var txtRingtoneValue: TextView
    private lateinit var txtAutoAnswerValue: TextView
    private lateinit var txtSessionInfo: TextView
    private lateinit var settingLanguageLayout: LinearLayout
    private lateinit var settingRingtoneLayout: LinearLayout
    private lateinit var settingAutoAnswerLayout: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_settings))

        settings = SettingsManager.getInstance(requireContext())

        txtLanguageValue = view.findViewById(R.id.txt_language_value)
        txtRingtoneValue = view.findViewById(R.id.txt_ringtone_value)
        txtAutoAnswerValue = view.findViewById(R.id.txt_auto_answer_value)
        txtSessionInfo = view.findViewById(R.id.txt_session_info)
        settingLanguageLayout = view.findViewById(R.id.setting_language)
        settingRingtoneLayout = view.findViewById(R.id.setting_ringtone)
        settingAutoAnswerLayout = view.findViewById(R.id.setting_auto_answer)
        updateAllValues()

        viewModel.sessionInfo.observe(viewLifecycleOwner) { info ->
            txtSessionInfo.text = buildString {
                append("E-post: ${info["email"]}\n")
                append("Bekreftet: ${if (info["emailConfirmed"] == "true") "Ja" else "Nei"}\n")
                append("Økt alder: ${info["sessionAge"]}")
            }
        }
        viewModel.loadSessionInfo()

        view.findViewById<LinearLayout>(R.id.setting_language).setOnClickListener {
            showLanguageDialog()
        }

        view.findViewById<LinearLayout>(R.id.setting_ringtone).setOnClickListener {
            settings.ringtoneEnabled = !settings.ringtoneEnabled
            updateRingtoneValue()
        }

        view.findViewById<LinearLayout>(R.id.setting_auto_answer).setOnClickListener {
            settings.autoAnswer = !settings.autoAnswer
            updateAutoAnswerValue()
        }

        val accountHeader = view.findViewById<LinearLayout>(R.id.setting_account_header)
        val accountContent = view.findViewById<LinearLayout>(R.id.account_info_content)
        val chevron = view.findViewById<TextView>(R.id.txt_account_chevron)

        fun updateAccordionDescription(expanded: Boolean) {
            val action = if (expanded) getString(R.string.accordion_hide) else getString(R.string.accordion_show)
            accountHeader.contentDescription = "${getString(R.string.settings_account_info)}, $action"
        }

        updateAccordionDescription(expanded = false)

        accountHeader.setOnClickListener {
            val expanded = accountContent.visibility == View.VISIBLE
            accountContent.visibility = if (expanded) View.GONE else View.VISIBLE
            chevron.text = if (expanded) "▼" else "▲"
            updateAccordionDescription(expanded = !expanded)
        }

        view.findViewById<TextView>(R.id.btn_go_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<TextView>(R.id.btn_logout_settings).setOnClickListener {
            (activity as? MainActivity)?.showLogoutConfirmDialog()
        }

        (activity as? MainActivity)?.hideHeader()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.showHeader()
    }

    private fun updateAllValues() {
        updateLanguageValue()
        updateRingtoneValue()
        updateAutoAnswerValue()
    }

    private fun updateRingtoneValue() {
        val value = if (settings.ringtoneEnabled) getString(R.string.setting_on) else getString(R.string.setting_off)
        txtRingtoneValue.text = value
        settingRingtoneLayout.contentDescription = "${getString(R.string.settings_ringtone)}: $value"
    }

    private fun updateLanguageValue() {
        val value = when (settings.language) {
            "en" -> "English"
            else -> "Norsk"
        }
        txtLanguageValue.text = value
        settingLanguageLayout.contentDescription = "${getString(R.string.settings_language)}: $value"
    }

    private fun updateAutoAnswerValue() {
        val value = if (settings.autoAnswer) getString(R.string.setting_on) else getString(R.string.setting_off)
        txtAutoAnswerValue.text = value
        settingAutoAnswerLayout.contentDescription = "${getString(R.string.settings_auto_answer)}: $value"
    }

    private fun showLanguageDialog() {
        val options = arrayOf("Norsk", "English")
        val currentIndex = if (settings.language == "en") 1 else 0

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_language))
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val newLang = if (which == 1) "en" else "nb"
                if (newLang != settings.language) {
                    settings.language = newLang
                    dialog.dismiss()
                    activity?.recreate()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
