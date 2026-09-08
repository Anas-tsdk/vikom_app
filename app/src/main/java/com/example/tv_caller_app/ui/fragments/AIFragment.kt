package com.example.tv_caller_app.ui.fragments

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Window
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.graphics.toColorInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.activities.OutgoingCallActivity
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel

class AIFragment : Fragment() {

    private val authViewModel: AuthViewModel by activityViewModels {
        val app = requireActivity().application as TVCallerApplication
        AuthViewModel.factory(app.getAuthRepository())
    }

    private data class TopicContent(
        val emoji: String,
        val title: String,
        val subtitle: String,
        val date: String,
        val items: List<String>,
        val gridItems: List<Pair<Int, String>> = emptyList() 
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_ai))

        (activity as? MainActivity)?.hideHeader()

        val username = authViewModel.cachedUsername.value ?: getString(R.string.app_name)
        view.findViewById<TextView>(R.id.txt_ai_greeting).text = getString(R.string.ai_greeting, username)

        view.findViewById<TextView>(R.id.btn_ai_confirm).apply {
            announceAsButton()
            setOnClickListener { parentFragmentManager.popBackStack() }
        }

        val dateFormatted = SimpleDateFormat("dd. MMMM", Locale.forLanguageTag("no")).format(Date())
        val today = "I dag ($dateFormatted)"

        view.findViewById<LinearLayout>(R.id.topic_medicine).setOnClickListener {
            showTopicDialog(TopicContent(
                emoji = "💊",
                title = "Medisin",
                subtitle = "Her er medisinene dine for i dag",
                date = today,
                items = listOf(
                    "08:00  Paracetamol 500mg",
                    "14:00  Blodtrykk-medisin",
                    "20:00  Sovemedisin"
                )
            ))
        }

        view.findViewById<LinearLayout>(R.id.topic_appointments).setOnClickListener {
            showTopicDialog(TopicContent(
                emoji = "📅",
                title = "Avtaler",
                subtitle = "Her er oversikt over avtalene dine",
                date = today,
                items = listOf(
                    "10:00  Legetime hos Dr. Hansen i Ryen",
                    "12:00  Frisørtime",
                    "18:00  Middag hos Annelin"
                )
            ))
        }

        view.findViewById<LinearLayout>(R.id.topic_daily).setOnClickListener {
            showTopicDialog(TopicContent(
                emoji = "🧑‍💼",
                title = "Daglige gjøremål",
                subtitle = "Her er gjøremålene dine for i dag",
                date = today,
                items = listOf(
                    "08:00  Ta morgenmedisin",
                    "10:00  Handle mat",
                    "14:00  Ring legen",
                    "20:00  Ta kveldsmedisin"
                )
            ))
        }

        view.findViewById<LinearLayout>(R.id.topic_help).setOnClickListener {
            showTopicDialog(TopicContent(
                emoji = "📞",
                title = "Hjelp",
                subtitle = "Her er viktige kontakter",
                date = "",
                items = emptyList(),
                gridItems = listOf(
                    R.drawable.ic_acc_hospital to "Legevakt",
                    R.drawable.ic_acc_doctor to "Legekontor",
                    R.drawable.ic_acc_fire to "Brannvesen",
                    R.drawable.ic_acc_home to "Hjemmetjenesten",
                    R.drawable.ic_acc_police to "Politi"
                )
            ))
        }

        view.findViewById<LinearLayout>(R.id.topic_medicine).requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.showHeader()
    }

    private fun showTopicDialog(content: TopicContent) {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_ai_topic)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setCancelable(true)

        dialog.findViewById<TextView>(R.id.txt_topic_title).text = content.title
        dialog.findViewById<TextView>(R.id.txt_topic_subtitle).text = content.subtitle

        val dateView = dialog.findViewById<TextView>(R.id.txt_topic_date)
        if (content.date.isNotEmpty()) {
            dateView.text = content.date
            dateView.visibility = View.VISIBLE
        } else {
            dateView.visibility = View.GONE
        }

        val container = dialog.findViewById<LinearLayout>(R.id.items_container)
        val px8 = (8 * resources.displayMetrics.density).toInt()
        val px16 = (16 * resources.displayMetrics.density).toInt()
        val px4 = (4 * resources.displayMetrics.density).toInt()

        if (content.gridItems.isNotEmpty()) {
            buildHelpGrid(container, content, dialog, px8, px16)
        } else {
            content.items.forEach { item ->
                val itemView = TextView(requireContext()).apply {
                    text = item
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_primary))
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(px8, px16, px8, px16)
                    isFocusable = true
                    isClickable = false
                    background = resources.getDrawable(R.drawable.button_dialog_item, null)
                }
                container.addView(itemView)

                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px4 / 4)
                    setBackgroundColor("#DDDDDD".toColorInt())
                }
                container.addView(divider)
            }
        }

        val btnClose = dialog.findViewById<TextView>(R.id.btn_topic_close)
        btnClose.announceAsButton()
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.6).toInt()
        val height = (displayMetrics.heightPixels * 0.8).toInt()
        dialog.window?.setLayout(width, height)

        val firstItem = container.getChildAt(0)
        (firstItem ?: btnClose).requestFocus()
    }

    private fun buildHelpGrid(
        container: LinearLayout,
        content: TopicContent,
        dialog: Dialog,
        px8: Int,
        px16: Int
    ) {
        container.removeAllViews()
        val chunked = content.gridItems.chunked(2)
        chunked.forEach { row ->
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, px8) }
            }
            row.forEach { (iconRes, label) ->
                val cell = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px16, px16, px16, px16)
                    background = resources.getDrawable(R.drawable.button_settings_item, null)
                    isFocusable = true
                    isClickable = true
                    contentDescription = label
                    announceAsButton()
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).also { it.setMargins(0, 0, if (row.indexOf(iconRes to label) == 0 && row.size > 1) px8 else 0, 0) }
                    setOnClickListener {
                        showConfirmationInDialog(dialog, container, content, label, iconRes, px8, px16)
                    }
                }
                val iconView = android.widget.ImageView(requireContext()).apply {
                    setImageResource(iconRes)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(px16 * 2, px16 * 2)
                        .also { it.setMargins(0, 0, px16, 0) }
                }
                val labelView = TextView(requireContext()).apply {
                    text = label
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_primary))
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                cell.addView(iconView)
                cell.addView(labelView)
                rowLayout.addView(cell)
            }
            if (row.size == 1) {
                val filler = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                rowLayout.addView(filler)
            }
            container.addView(rowLayout)
        }
    }

    private fun showConfirmationInDialog(
        dialog: Dialog,
        container: LinearLayout,
        content: TopicContent,
        contactName: String,
        iconRes: Int,
        px8: Int,
        px16: Int
    ) {
        val titleView = dialog.findViewById<TextView>(R.id.txt_topic_title)
        val subtitleView = dialog.findViewById<TextView>(R.id.txt_topic_subtitle)
        val dateView = dialog.findViewById<TextView>(R.id.txt_topic_date)
        val btnClose = dialog.findViewById<TextView>(R.id.btn_topic_close)

        titleView.text = "Ring $contactName?"
        subtitleView.text = "Du vil nå ringe $contactName."
        dateView.visibility = View.GONE

        container.removeAllViews()
        container.gravity = android.view.Gravity.CENTER

        val iconView = android.widget.ImageView(requireContext()).apply {
            setImageResource(iconRes)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        container.addView(iconView)

        btnClose.visibility = View.GONE

        val buttonRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnConfirm = TextView(requireContext()).apply {
            text = "Ja, ring"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tab_text_size))
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(px16, px16, px16, px16)
            background = resources.getDrawable(R.drawable.button_primary, null)
            isFocusable = true
            isClickable = true
            announceAsButton()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.setMargins(0, 0, px8, 0) }
            setOnClickListener {
                dialog.dismiss()
                val intent = OutgoingCallActivity.createIntent(
                    context = requireContext(),
                    contactId = "ai_help_${contactName.lowercase()}",
                    contactName = contactName,
                    callId = "ai_${System.currentTimeMillis()}"
                )
                startActivity(intent)
            }
        }

        val btnCancel = TextView(requireContext()).apply {
            text = "Avbryt"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tab_text_size))
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(px16, px16, px16, px16)
            background = resources.getDrawable(R.drawable.button_secondary, null)
            isFocusable = true
            isClickable = true
            announceAsButton()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.setMargins(px8, 0, 0, 0) }
            setOnClickListener {
                titleView.text = content.title
                subtitleView.text = content.subtitle
                dateView.visibility = View.GONE
                btnClose.visibility = View.VISIBLE
                btnClose.announceAsButton()
                btnClose.setOnClickListener { dialog.dismiss() }
                val parent = buttonRow.parent as? LinearLayout
                parent?.removeView(buttonRow)
                container.gravity = android.view.Gravity.TOP
                buildHelpGrid(container, content, dialog, px8, px16)
                container.getChildAt(0)?.requestFocus()
            }
        }

        buttonRow.addView(btnConfirm)
        buttonRow.addView(btnCancel)

        val dialogRoot = btnClose.parent as LinearLayout
        val closeIndex = dialogRoot.indexOfChild(btnClose)
        dialogRoot.addView(buttonRow, closeIndex)

        btnConfirm.requestFocus()
    }

    private fun View.announceAsButton() {
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
    }
}
