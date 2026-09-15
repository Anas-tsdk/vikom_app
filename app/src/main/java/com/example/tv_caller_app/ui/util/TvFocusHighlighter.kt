package com.example.tv_caller_app.ui.util

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import java.util.WeakHashMap

/**
 * Adds one consistent, high-contrast focus treatment to every Activity.
 *
 * Most screens already use focused drawables, but their contrast varies and a
 * few clickable containers have no focused background at all. A global focus
 * listener keeps the original UI intact while adding a white/navy double ring,
 * a small scale increase and elevation whenever navigation comes from a remote
 * or keyboard. Touch focus is deliberately ignored.
 */
object TvFocusHighlighter : Application.ActivityLifecycleCallbacks {

    private data class Registration(
        val root: View,
        val listener: ViewTreeObserver.OnGlobalFocusChangeListener
    )

    private data class OriginalVisuals(
        val scaleX: Float,
        val scaleY: Float,
        val elevation: Float
    )

    private val registrations = WeakHashMap<Activity, Registration>()
    private val rings = WeakHashMap<View, Drawable>()
    private val originalVisuals = WeakHashMap<View, OriginalVisuals>()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val root = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (oldFocus !== newFocus) removeHighlight(oldFocus)
            if (newFocus != null && newFocus.isEnabled && newFocus.visibility == View.VISIBLE && !newFocus.isInTouchMode) {
                addHighlight(newFocus)
            }
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        registrations[activity] = Registration(root, listener)
    }

    override fun onActivityDestroyed(activity: Activity) {
        registrations.remove(activity)?.let { registration ->
            if (registration.root.viewTreeObserver.isAlive) {
                registration.root.viewTreeObserver.removeOnGlobalFocusChangeListener(registration.listener)
            }
            removeHighlight(registration.root.findFocus())
        }
    }

    private fun addHighlight(view: View) {
        if (rings.containsKey(view)) return

        originalVisuals.putIfAbsent(
            view,
            OriginalVisuals(view.scaleX, view.scaleY, view.elevation)
        )
        val ring = HighContrastFocusRing(view.resources.displayMetrics.density)
        rings[view] = ring

        view.post {
            if (!view.hasFocus() || view.isInTouchMode || rings[view] !== ring) return@post
            ring.setBounds(0, 0, view.width, view.height)
            view.overlay.add(ring)
            view.animate()
                .scaleX(originalVisuals[view]?.scaleX?.times(FOCUS_SCALE) ?: FOCUS_SCALE)
                .scaleY(originalVisuals[view]?.scaleY?.times(FOCUS_SCALE) ?: FOCUS_SCALE)
                .translationZ(dp(view, FOCUS_LIFT_DP))
                .setDuration(FOCUS_ANIMATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun removeHighlight(view: View?) {
        if (view == null) return
        rings.remove(view)?.let(view.overlay::remove)
        val original = originalVisuals[view] ?: return
        view.animate()
            .scaleX(original.scaleX)
            .scaleY(original.scaleY)
            .translationZ(0f)
            .setDuration(FOCUS_ANIMATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        view.elevation = original.elevation
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) {
        activity.window.decorView.findFocus()?.let { focused ->
            if (focused.isEnabled && !focused.isInTouchMode) addHighlight(focused)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        removeHighlight(activity.window.decorView.findFocus())
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private class HighContrastFocusRing(density: Float) : Drawable() {
        private val boundsRect = RectF()
        private val outerWidth = 6f * density
        private val innerWidth = 2.5f * density
        private val radius = 16f * density
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = outerWidth
            color = Color.WHITE
        }
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = innerWidth
            color = Color.rgb(30, 33, 76)
        }

        override fun draw(canvas: Canvas) {
            val outerInset = outerWidth / 2f
            boundsRect.set(
                bounds.left + outerInset,
                bounds.top + outerInset,
                bounds.right - outerInset,
                bounds.bottom - outerInset
            )
            canvas.drawRoundRect(boundsRect, radius, radius, outerPaint)

            val innerInset = outerWidth + innerWidth / 2f
            boundsRect.set(
                bounds.left + innerInset,
                bounds.top + innerInset,
                bounds.right - innerInset,
                bounds.bottom - innerInset
            )
            canvas.drawRoundRect(boundsRect, radius, radius, innerPaint)
        }

        override fun setAlpha(alpha: Int) {
            outerPaint.alpha = alpha
            innerPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            outerPaint.colorFilter = colorFilter
            innerPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private const val FOCUS_SCALE = 1.055f
    private const val FOCUS_LIFT_DP = 8f
    private const val FOCUS_ANIMATION_MS = 110L
}
