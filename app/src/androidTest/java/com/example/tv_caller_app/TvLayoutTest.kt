package com.example.tv_caller_app

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun tvContext(width: Int, height: Int, density: Int, fontScale: Float): ContextThemeWrapper {
        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
            screenWidthDp = width
            screenHeightDp = height
            smallestScreenWidthDp = minOf(width, height)
            densityDpi = density
            this.fontScale = fontScale
            orientation = Configuration.ORIENTATION_LANDSCAPE
            uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(
            instrumentation.targetContext.createConfigurationContext(configuration),
            R.style.Theme_TV_caller_app
        )
    }

    @Test
    fun tvDimensionsDoNotFallBackToTabletSizes() {
        for ((width, height) in listOf(640 to 360, 960 to 540, 1280 to 720, 1920 to 1080)) {
            for (density in listOf(160, 240, 320)) {
                val context = tvContext(width, height, density, 1f)
                val buttonDp = context.resources.getDimension(R.dimen.button_call_action) /
                    context.resources.displayMetrics.density
                assertEquals("TV viewport ${width}x$height", if (height >= 720) 100f else 80f, buttonDp, 0.1f)
            }
        }
    }

    @Test
    fun homeAndHeaderRemainReachableAcrossTvViewportsAndLargeFonts() {
        instrumentation.runOnMainSync {
            for ((width, height) in listOf(640 to 360, 960 to 540, 1280 to 720, 1920 to 1080)) {
                for (fontScale in listOf(1f, 1.5f)) {
                    val context = tvContext(width, height, 160, fontScale)
                    val density = context.resources.displayMetrics.density
                    val widthPx = (width * density).toInt()
                    val heightPx = (height * density).toInt()
                    val main = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
                    main.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
                    )
                    main.layout(0, 0, widthPx, heightPx)
                    val headerLeft = main.findViewById<View>(R.id.header_left)
                    val headerRight = main.findViewById<HorizontalScrollView>(R.id.header_right)
                    assertTrue(headerRight.width > 0)
                    assertTrue(headerLeft.right <= headerRight.left)
                    val content = main.findViewById<View>(R.id.main_browse_fragment)
                    assertEquals(heightPx, content.bottom)
                    assertTrue(content.height > 0)

                    val home = LayoutInflater.from(context).inflate(R.layout.fragment_home, null) as ScrollView
                    home.findViewById<View>(R.id.txt_hero_state).visibility = View.VISIBLE
                    home.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(content.height, View.MeasureSpec.EXACTLY)
                    )
                    home.layout(0, 0, widthPx, content.height)
                    val tile = home.findViewById<View>(R.id.tile_contacts)
                    assertTrue(tile.width > 0 && tile.height > 0)
                    home.requestChildFocus(tile, tile)
                    home.scrollTo(0, home.getChildAt(0).height)
                    assertTrue(home.scrollY + home.height >= home.getChildAt(0).height)
                }
            }
        }
    }
}
