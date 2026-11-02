package com.example.tv_caller_app

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageDialogLayoutTest {
    @Test
    fun allSevenLanguagesCanBeRevealedWhileCancelStaysVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for ((height, font) in listOf(324 to 1f, 486 to 1.5f, 648 to 1.5f)) {
                val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    densityDpi = 160
                    fontScale = font
                }
                val context = ContextThemeWrapper(
                    instrumentation.targetContext.createConfigurationContext(configuration), R.style.Theme_TV_caller_app
                )
                val inflater = LayoutInflater.from(context)
                val dialog = inflater.inflate(R.layout.dialog_language_selection, null)
                val container = dialog.findViewById<LinearLayout>(R.id.language_options_container)
                for (label in listOf(R.string.language_norwegian, R.string.language_english,
                    R.string.language_german, R.string.language_swedish, R.string.language_finnish,
                    R.string.language_dutch, R.string.language_french)) {
                    val row = inflater.inflate(R.layout.view_language_option, container, false)
                    row.findViewById<TextView>(R.id.txt_language_option_label).setText(label)
                    container.addView(row)
                }
                dialog.measure(View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                dialog.layout(0, 0, dialog.measuredWidth, dialog.measuredHeight)
                val scroll = dialog.findViewById<ScrollView>(R.id.language_options_scroll)
                val cancel = dialog.findViewById<View>(R.id.btn_language_cancel)
                assertTrue("The list needs a viewport", scroll.height > 0)
                assertTrue("Cancel must remain inside the dialog", cancel.bottom <= dialog.height)
                assertTrue("Cancel must not overlap the list", cancel.top >= scroll.bottom)
                assertFalse("The viewport must not take remote focus", scroll.isFocusable)
                for (i in 0 until container.childCount) {
                    val row = container.getChildAt(i)
                    scroll.scrollTo(0, row.top)
                    assertTrue("Language $i must fit horizontally", row.width <= scroll.width)
                    assertTrue("Language $i must be revealed", row.top >= scroll.scrollY &&
                        row.bottom <= scroll.scrollY + scroll.height)
                }
                if (height == 324) assertTrue("Small TV must scroll", scroll.scrollY > 0)
            }
        }
    }
}
