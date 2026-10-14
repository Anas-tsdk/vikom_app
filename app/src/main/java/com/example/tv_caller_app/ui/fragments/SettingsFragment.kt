package com.example.tv_caller_app.ui.fragments

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
            txtSessionInfo.text = getString(
                R.string.settings_session_info_format,
                info["email"].orEmpty(),
                getString(if (info["emailConfirmed"] == "true") R.string.yes else R.string.no),
                info["sessionAge"].orEmpty()
            )
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
            (activity as? MainActivity)?.showHome()
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
            "en" -> getString(R.string.language_english)
            "de" -> getString(R.string.language_german)
            "sv" -> getString(R.string.language_swedish)
            "fi" -> getString(R.string.language_finnish)
            "nl" -> getString(R.string.language_dutch)
            "fr" -> getString(R.string.language_french)
            else -> getString(R.string.language_norwegian)
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
        val options = listOf(
            "nb" to R.string.language_norwegian,
            "en" to R.string.language_english,
            "de" to R.string.language_german,
            "sv" to R.string.language_swedish,
            "fi" to R.string.language_finnish,
            "nl" to R.string.language_dutch,
            "fr" to R.string.language_french
        )

        val dialog = Dialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_language_selection)
            setCanceledOnTouchOutside(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val container = dialog.findViewById<LinearLayout>(R.id.language_options_container)
        var selectedRow: View? = null
        options.forEachIndexed { index, (code, labelRes) ->
            val row = layoutInflater.inflate(R.layout.view_language_option, container, false)
            val selected = settings.language == code || (code == "nb" && settings.language == "no")
            row.findViewById<TextView>(R.id.txt_language_option_label).setText(labelRes)
            row.findViewById<TextView>(R.id.txt_language_option_check).visibility =
                if (selected) View.VISIBLE else View.GONE
            row.setBackgroundResource(
                if (selected) R.drawable.language_option_selected else R.drawable.button_dialog_item
            )
            row.contentDescription = getString(
                if (selected) R.string.language_selected_accessibility
                else R.string.language_option_accessibility,
                getString(labelRes)
            )
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { if (index > 0) it.topMargin = resources.getDimensionPixelSize(R.dimen.padding_small) }
            row.setOnClickListener {
                if (selected) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                settings.language = code
                dialog.dismiss()
                activity?.recreate()
            }
            container.addView(row)
            if (selected) selectedRow = row
        }
        dialog.findViewById<TextView>(R.id.btn_language_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
        val maxWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        val preferred = (440 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(preferred.coerceAtMost(maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        (selectedRow ?: container.getChildAt(0))?.requestFocus()
    }
}
