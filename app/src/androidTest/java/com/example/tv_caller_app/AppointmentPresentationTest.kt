package com.example.tv_caller_app

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tv_caller_app.domain.Appointment
import com.example.tv_caller_app.settings.SettingsManager
import com.example.tv_caller_app.ui.adapters.DayGroupedAppointmentAdapter
import com.example.tv_caller_app.ui.util.localizedString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppointmentPresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun context(language: String, fontScale: Float = 1f): ContextThemeWrapper {
        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            this.fontScale = fontScale
            densityDpi = 160
        }
        return ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(configuration),
            R.style.Theme_TV_caller_app)
    }

    @Test
    fun cardsWrapBadgesAndActionsAcrossLanguagesAndLargeFonts() {
        instrumentation.runOnMainSync {
            for (language in listOf("nb", "en", "fr", "de", "sv", "fi", "nl")) {
                for (font in listOf(1f, 1.5f)) {
                    for (width in listOf(360, 640, 960)) {
                        val row = LayoutInflater.from(context(language, font))
                            .inflate(R.layout.item_appointment_day, null)
                        row.findViewById<TextView>(R.id.txt_visit_time).text = "09:00 – 10:30"
                        row.findViewById<TextView>(R.id.txt_visit_personnel).text =
                            "Alexandra Charlotte van der Berg — Équipe de soins à domicile"
                        val status = row.findViewById<TextView>(R.id.txt_visit_status).apply {
                            setText(R.string.appointment_status_notcompleted)
                        }
                        val response = row.findViewById<TextView>(R.id.txt_response_status).apply {
                            setText(R.string.appointment_response_pending)
                        }
                        row.findViewById<View>(R.id.response_actions).visibility = View.VISIBLE
                        measure(row, width)
                        val label = "$language font=$font width=$width"
                        assertSeparatedAndContained(status, response, label)
                        assertSeparatedAndContained(row.findViewById(R.id.btn_decline_appointment),
                            row.findViewById(R.id.btn_accept_appointment), label)
                        for (id in listOf(R.id.txt_visit_time, R.id.txt_visit_personnel,
                            R.id.txt_visit_status, R.id.txt_response_status,
                            R.id.btn_decline_appointment, R.id.btn_accept_appointment)) {
                            val text = row.findViewById<TextView>(id)
                            assertTrue(label, text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                            for (line in 0 until text.layout.lineCount) {
                                assertEquals(label, 0, text.layout.getEllipsisCount(line))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun updatesKeepExpandedAppointmentWhenItsPositionChanges() {
        instrumentation.runOnMainSync {
            val first = Appointment(id = 41, date = LocalDate.now().plusDays(2).toString(),
                startTime = "09:00", endTime = "10:00", tasks = "A long task description")
            val now = LocalDateTime.now()
            val adapter = DayGroupedAppointmentAdapter(DayGroupedAppointmentAdapter.build(listOf(first), now)) { _, _ -> }
            val list = RecyclerView(context("en")).apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                this.adapter = adapter
            }
            measure(list, 960, 2000)
            val firstHolder = list.findViewHolderForItemId(41)!!
            firstHolder.itemView.findViewById<View>(R.id.visit_card).performClick()
            measure(list, 960, 2000)
            assertEquals(Int.MAX_VALUE, list.findViewHolderForItemId(41)!!.itemView
                .findViewById<TextView>(R.id.txt_visit_tasks).maxLines)
            val card = list.findViewHolderForItemId(41)!!.itemView.findViewById<View>(R.id.visit_card)
            assertTrue(card.requestFocus())
            assertTrue(card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
            val decline = card.findViewById<View>(R.id.btn_decline_appointment)
            assertTrue(decline.hasFocus())
            assertTrue(decline.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
            assertTrue(card.findViewById<View>(R.id.btn_accept_appointment).hasFocus())
            val earlier = first.copy(id = 40, startTime = "08:00")
            adapter.update(DayGroupedAppointmentAdapter.build(listOf(earlier, first), now), 41)
            measure(list, 960, 2000)
            val updated = list.findViewHolderForItemId(41)!!.itemView
            assertEquals(Int.MAX_VALUE, updated.findViewById<TextView>(R.id.txt_visit_tasks).maxLines)
            assertFalse(updated.findViewById<View>(R.id.btn_accept_appointment).isEnabled)
            assertTrue(updated.findViewById<View>(R.id.visit_card).isFocused)
            adapter.update(DayGroupedAppointmentAdapter.build(listOf(earlier, first), now), null)
            measure(list, 960, 2000)
            assertTrue(list.findViewHolderForItemId(41)!!.itemView
                .findViewById<View>(R.id.btn_accept_appointment).isEnabled)
        }
    }

    @Test
    fun viewModelFeedbackUsesChosenLanguageRatherThanContextLanguage() {
        val english = context("en")
        val settings = SettingsManager.getInstance(english)
        val previous = settings.language
        try {
            settings.language = "fr"
            assertEquals("Nom d’utilisateur mis à jour", english.localizedString(R.string.username_updated))
            settings.language = "en"
            assertEquals("Username updated", english.localizedString(R.string.username_updated))
        } finally {
            settings.language = previous
        }
    }

    private fun assertSeparatedAndContained(a: View, b: View, label: String) {
        val parent = a.parent as ViewGroup
        assertTrue(label, a.left >= 0 && a.right <= parent.width && a.bottom <= parent.height)
        assertTrue(label, b.left >= 0 && b.right <= parent.width && b.bottom <= parent.height)
        assertTrue(label, a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
    }

    private fun measure(view: View, width: Int, height: Int? = null) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height ?: 0,
                if (height == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
