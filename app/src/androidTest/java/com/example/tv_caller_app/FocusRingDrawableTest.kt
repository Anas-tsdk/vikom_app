package com.example.tv_caller_app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tv_caller_app.ui.util.FocusRingDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusRingDrawableTest {
    @Test
    fun headerRingFollowsSquareLeftCornersAndRoundedRightCorners() {
        val background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(0f, 0f, 24f, 24f, 24f, 24f, 0f, 0f)
            setBounds(0, 0, 200, 80)
        }
        val bitmap = render(background)
        assertTrue(Color.alpha(bitmap.getPixel(1, 1)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(198, 1)))
        assertEquals(0, Color.alpha(bitmap.getPixel(100, 40)))
        // One 3px border, without a second inner outline.
        assertTrue(Color.alpha(bitmap.getPixel(1, 40)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(7, 40)))
    }

    @Test
    fun circularButtonsKeepCircularRingAndInsetBackgroundKeepsItsBounds() {
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setBounds(10, 10, 70, 70)
        }
        val bitmap = render(oval)
        assertEquals(0, Color.alpha(bitmap.getPixel(10, 10)))
        assertTrue(Color.alpha(bitmap.getPixel(40, 11)) > 0)
        assertEquals(0, Color.alpha(bitmap.getPixel(40, 40)))
        assertEquals(0, Color.alpha(bitmap.getPixel(40, 1)))
    }

    @Test
    fun removingLegacyBorderDoesNotChangeOtherButtonsUsingSameResource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = ContextCompat.getDrawable(context, R.drawable.button_profile_container)!!
        val focused = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
        original.state = focused
        original.setBounds(0, 0, 200, 80)
        val before = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        original.draw(Canvas(before))
        val copy = original.constantState!!.newDrawable(context.resources).mutate()
        copy.state = focused
        copy.setBounds(0, 0, 200, 80)
        FocusRingDrawable.removeBackgroundStroke(copy)
        val after = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        copy.draw(Canvas(after))
        assertNotEquals(before.getPixel(0, 40), after.getPixel(0, 40))
        val unchanged = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        original.draw(Canvas(unchanged))
        assertEquals(before.getPixel(0, 40), unchanged.getPixel(0, 40))
        assertEquals(before.getPixel(100, 40), after.getPixel(100, 40))
    }

    private fun render(background: GradientDrawable): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        FocusRingDrawable(1f, Color.MAGENTA).apply {
            setBounds(0, 0, 200, 80)
            followBackground(background)
            draw(Canvas(bitmap))
        }
        return bitmap
    }
}
