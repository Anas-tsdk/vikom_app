package com.example.tv_caller_app

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tv_caller_app.ui.util.TvFocusHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvFocusClippingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = ContextThemeWrapper(
        instrumentation.targetContext, R.style.Theme_TV_caller_app
    )

    @Test
    fun edgeCardsKeepTheirWholeBorderAndSmallButtonsStillZoom() {
        instrumentation.runOnMainSync {
            val root = FrameLayout(context)
            val button = View(context)
            root.addView(button, FrameLayout.LayoutParams(200, 80))
            measure(root, 400, 200)
            // Flush against the top/left: the existing size fits, enlargement doesn't.
            assertEquals(1f, TvFocusHighlighter.fittingScale(button, 1.075f), 0.001f)
            button.layout(100, 60, 300, 140)
            assertEquals(1.075f, TvFocusHighlighter.fittingScale(button, 1.075f), 0.001f)
            // A resize/rebind while still focused must recalculate the safe zoom.
            button.layout(0, 0, 400, 100)
            assertEquals(1f, TvFocusHighlighter.fittingScale(button, 1.075f), 0.001f)
        }
    }

    @Test
    fun clippingAncestorsScrollOffsetsAndCustomPivotsLimitZoom() {
        instrumentation.runOnMainSync {
            val root = FrameLayout(context)
            val wrapper = FrameLayout(context).apply { clipChildren = false }
            val button = View(context)
            root.addView(wrapper, FrameLayout.LayoutParams(400, 200))
            wrapper.addView(button, FrameLayout.LayoutParams(200, 80))
            measure(root, 400, 200)
            button.layout(190, 60, 390, 140)
            assertEquals(1.075f, TvFocusHighlighter.fittingScale(button, 1.075f), 0.001f)
            button.translationX = 8f
            assertEquals(1.02f, TvFocusHighlighter.fittingScale(button, 1.075f), 0.001f)
            wrapper.scrollTo(8, 0)
            assertEquals(1.075f, TvFocusHighlighter.fittingScale(button, 1.075f), 0.001f)
            button.pivotX = 0f
            assertEquals(1.05f, TvFocusHighlighter.fittingScale(button, 1.075f), 0.001f)
            root.clipBounds = Rect(0, 0, 390, 200)
            assertEquals(1f, TvFocusHighlighter.fittingScale(button, 1.075f), 0.001f)
        }
    }

    @Test
    fun settingsAndProfileKeepEveryActionAtItsOriginalSize() {
        instrumentation.runOnMainSync {
            for (isTv in listOf(true, false)) {
                for (width in listOf(640, 960, 1280, 1920)) {
                    for (fontScale in listOf(1f, 1.5f)) {
                        val configuration = Configuration(context.resources.configuration).apply {
                            densityDpi = 160
                            screenWidthDp = width
                            screenHeightDp = width * 9 / 16
                            smallestScreenWidthDp = screenHeightDp
                            orientation = Configuration.ORIENTATION_LANDSCAPE
                            uiMode = if (isTv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
                            this.fontScale = fontScale
                        }
                        val themed = ContextThemeWrapper(context.createConfigurationContext(configuration),
                            R.style.Theme_TV_caller_app)
                        for (layout in listOf(R.layout.fragment_settings, R.layout.fragment_profile)) {
                            val root = FrameLayout(themed)
                            val page = LayoutInflater.from(themed).inflate(layout, root, false)
                            root.addView(page)
                            measure(root, width, width * 9 / 16)
                            val actions = descendants(page).filter { it.isFocusable && it.isClickable }
                            assertEquals(if (layout == R.layout.fragment_settings) 6 else 3, actions.size)
                            for (action in actions) {
                                val label = "layout=$layout action=${action.id} width=$width font=$fontScale tv=$isTv"
                                // Reveal the last field as well as the first row.
                                var ancestor = action.parent
                                while (ancestor is ViewGroup) {
                                    if (ancestor is ScrollView) {
                                        val bounds = Rect(0, 0, action.width, action.height)
                                        ancestor.offsetDescendantRectToMyCoords(action, bounds)
                                        ancestor.scrollTo(0, bounds.top)
                                    }
                                    ancestor = ancestor.parent
                                }
                                val preferred = TvFocusHighlighter.preferredFocusScale(action)
                                assertEquals(label, 1f, preferred, 0f)
                                assertEquals(label, 1f, TvFocusHighlighter.fittingScale(action, preferred), 0f)
                                if (android.os.Build.VERSION.SDK_INT >= 26) {
                                    assertTrue(label, !action.defaultFocusHighlightEnabled)
                                }
                                val visible = Rect(0, 0, action.width, action.height)
                                assertTrue(label, (action.parent as ViewGroup).getChildVisibleRect(action, visible, null))
                                assertEquals(label, action.width, visible.width())
                                assertEquals(label, action.height, visible.height())
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun selectableElementsInEveryLayoutFitAfterFocusZoom() {
        instrumentation.runOnMainSync {
            var checked = 0
            // Includes activities, fragments, dialogs, reusable tiles and list rows.
            for (width in listOf(640, 960, 1280, 1920)) {
                for (field in R.layout::class.java.fields) {
                    val root = FrameLayout(context)
                    val page = LayoutInflater.from(context).inflate(field.getInt(null), root, false)
                    root.addView(page)
                    measure(root, width, width * 9 / 16)
                    for (view in descendants(page)) {
                        if (!view.isFocusable || view.visibility != View.VISIBLE ||
                            view.width == 0 || view.height == 0) continue
                        val parent = view.parent as? ViewGroup ?: continue
                        val visible = Rect(0, 0, view.width, view.height)
                        if (!parent.getChildVisibleRect(view, visible, null) ||
                            visible.width() < view.width || visible.height() < view.height) continue
                        val scale = TvFocusHighlighter.fittingScale(view, TvFocusHighlighter.preferredFocusScale(view))
                        view.scaleX = scale
                        view.scaleY = scale
                        visible.set(0, 0, view.width, view.height)
                        val label = "${field.name}, view=${view.id}, width=$width, scale=$scale"
                        assertTrue(label, parent.getChildVisibleRect(view, visible, null))
                        assertTrue(label, visible.width() >= (view.width * scale).toInt() - 1)
                        assertTrue(label, visible.height() >= (view.height * scale).toInt() - 1)
                        view.scaleX = 1f
                        view.scaleY = 1f
                        checked++
                    }
                }
            }
            assertTrue("The audit must cover the selectable controls across screens", checked > 100)
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
}
