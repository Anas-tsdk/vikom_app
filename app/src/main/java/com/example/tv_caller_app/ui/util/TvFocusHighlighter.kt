package com.example.tv_caller_app.ui.util

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.tv_caller_app.R
import java.util.WeakHashMap

/**
 * Adds one consistent, high-contrast focus treatment to every Activity.
 *
 * Most screens already use focused drawables, but their contrast varies and a
 * few clickable containers have no focused background at all. A global focus
 * listener keeps the original UI intact while adding a brand-blue/navy TV ring,
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
            // Fragment replacement can lose the old-focus reference. Remove
            // every stale overlay, so only the current selection is highlighted.
            rings.keys.toList().filter { it !== newFocus }.forEach(::removeHighlight)
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
        // Scroll containers can receive framework focus, but are not actions.
        // Their viewport must never become a giant selected "button".
        if (view is android.widget.ScrollView || view is android.widget.HorizontalScrollView ||
            view is androidx.recyclerview.widget.RecyclerView) return
        if (rings.containsKey(view)) return

        originalVisuals.putIfAbsent(
            view,
            OriginalVisuals(view.scaleX, view.scaleY, view.elevation)
        )
        val isTv = (view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        if (isTv) prepareFocusSpace(view)
        val ring = HighContrastFocusRing(
            view.resources.displayMetrics.density,
            isTv,
            ContextCompat.getColor(view.context, R.color.color_brand_blue)
        )
        rings[view] = ring

        view.post {
            if (!view.hasFocus() || view.isInTouchMode || rings[view] !== ring) return@post
            // Measure after layout: initial TV focus may arrive before sizing.
            // Wide cards grow into the reserved margins, within clipping bounds.
            val preferredScale = if (isTv) TV_FOCUS_SCALE else FOCUS_SCALE
            // Scrolling normally considers only the unscaled button bounds.
            // Reveal the enlarged bounds before calculating the final zoom.
            val marginX = kotlin.math.ceil(view.width * (preferredScale - 1f) / 2f + dp(view, 8f)).toInt()
            val marginY = kotlin.math.ceil(view.height * (preferredScale - 1f) / 2f + dp(view, 8f)).toInt()
            view.requestRectangleOnScreen(
                Rect(-marginX, -marginY, view.width + marginX, view.height + marginY),
                true
            )
            val focusScale = fittingScale(view, preferredScale)
            ring.setBounds(0, 0, view.width, view.height)
            view.overlay.add(ring)
            view.animate()
                .scaleX(originalVisuals[view]?.scaleX?.times(focusScale) ?: focusScale)
                .scaleY(originalVisuals[view]?.scaleY?.times(focusScale) ?: focusScale)
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
        view.animate().cancel()
        view.scaleX = original.scaleX
        view.scaleY = original.scaleY
        view.translationZ = 0f
        view.elevation = original.elevation
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    private fun prepareFocusSpace(view: View) {
        var parent = view.parent as? ViewGroup
        while (parent != null && parent.id != android.R.id.content) {
            when (parent) {
                is android.widget.ScrollView,
                is android.widget.HorizontalScrollView,
                is androidx.recyclerview.widget.RecyclerView -> {
                    // Reserve room at the very first/last item as well as in
                    // the middle of a scrollable page. Keep viewport clipping.
                    val horizontal = dp(view, 32f).toInt()
                    val vertical = dp(view, 16f).toInt()
                    parent.setPadding(
                        maxOf(parent.paddingLeft, horizontal),
                        maxOf(parent.paddingTop, vertical),
                        maxOf(parent.paddingRight, horizontal),
                        maxOf(parent.paddingBottom, vertical)
                    )
                    parent.clipToPadding = false
                }
                is android.widget.LinearLayout,
                is android.widget.FrameLayout,
                is android.widget.RelativeLayout -> {
                    // Intermediate wrappers must allow a button to grow into
                    // those margins; the scrolling/window viewport still clips.
                    parent.clipChildren = false
                    parent.clipToPadding = false
                }
            }
            parent = parent.parent as? ViewGroup
        }
    }

    private fun fittingScale(view: View, preferredScale: Float): Float {
        if (view.width == 0 || view.height == 0) return 1f
        var scale = preferredScale
        var left = view.left.toFloat()
        var top = view.top.toFloat()
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            val centerX = left - parent.scrollX + view.width / 2f
            val centerY = top - parent.scrollY + view.height / 2f
            if (parent.clipChildren) {
                val minX = if (parent.clipToPadding) parent.paddingLeft.toFloat() else 0f
                val maxX = parent.width - if (parent.clipToPadding) parent.paddingRight.toFloat() else 0f
                val minY = if (parent.clipToPadding) parent.paddingTop.toFloat() else 0f
                val maxY = parent.height - if (parent.clipToPadding) parent.paddingBottom.toFloat() else 0f
                scale = minOf(scale, 2f * minOf(centerX - minX, maxX - centerX) / view.width)
                scale = minOf(scale, 2f * minOf(centerY - minY, maxY - centerY) / view.height)
            }
            left += parent.left - parent.scrollX
            top += parent.top - parent.scrollY
            parent = parent.parent as? ViewGroup
        }
        return scale.coerceIn(1f, preferredScale)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) {
        val currentFocus = activity.window.decorView.findFocus()
        rings.keys.toList().filter { it !== currentFocus }.forEach(::removeHighlight)
        currentFocus?.let { focused ->
            if (focused.isEnabled && !focused.isInTouchMode) addHighlight(focused)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        removeHighlight(activity.window.decorView.findFocus())
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private class HighContrastFocusRing(density: Float, isTv: Boolean, brandBlue: Int) : Drawable() {
        private val boundsRect = RectF()
        private val outerWidth = (if (isTv) 7f else 6f) * density
        private val innerWidth = (if (isTv) 3f else 2.5f) * density
        private val radius = 16f * density
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = outerWidth
            color = if (isTv) brandBlue else Color.WHITE
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
    private const val TV_FOCUS_SCALE = 1.075f
    private const val FOCUS_LIFT_DP = 8f
    private const val FOCUS_ANIMATION_MS = 110L
}
