package com.example.tv_caller_app.ui.util

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Matrix
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
 * listener keeps the original UI intact while adding one shape-matched violet ring,
 * a small scale increase and elevation whenever navigation comes from a remote
 * or keyboard. Touch focus is deliberately ignored.
 */
object TvFocusHighlighter : Application.ActivityLifecycleCallbacks {

    private data class Registration(
        val root: View,
        val listener: ViewTreeObserver.OnGlobalFocusChangeListener
    )

    private data class FocusDrawing(
        val observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnPreDrawListener
    )

    private data class OriginalVisuals(
        val scaleX: Float,
        val scaleY: Float,
        val elevation: Float,
        val translationZ: Float,
        val defaultFocusHighlight: Boolean
    )

    private data class BackgroundVisuals(val original: Drawable?, val managed: Drawable?)

    private val backgrounds = WeakHashMap<View, BackgroundVisuals>()
    private val registrations = WeakHashMap<Activity, Registration>()
    private val rings = WeakHashMap<View, Drawable>()
    private val drawListeners = WeakHashMap<View, FocusDrawing>()
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
            OriginalVisuals(view.scaleX, view.scaleY, view.elevation, view.translationZ,
                if (android.os.Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled else false)
        )
        if (android.os.Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled = false
        prepareBackground(view)
        val ring = FocusRingDrawable(
            view.resources.displayMetrics.density,
            ContextCompat.getColor(view.context, R.color.color_brand_blue)
        )
        rings[view] = ring

        val preferredScale = preferredFocusScale(view)
        var lastScale = Float.NaN
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (view.hasFocus() && !view.isInTouchMode && rings[view] === ring) {
                // Recheck after layout and scrolling, not just when focus arrives.
                // RecyclerView can resize/rebind a focused card without moving focus.
                prepareBackground(view)
                ring.followBackground(view.background)
                ring.setBounds(0, 0, view.width, view.height)
                val focusScale = fittingScale(view, preferredScale)
                if (focusScale != lastScale) {
                    val original = originalVisuals.getValue(view)
                    view.animate().cancel()
                    if (lastScale.isNaN() || focusScale < lastScale) {
                        // Clamp before this frame is drawn. An animation from an
                        // old, larger scale would still show clipped frames.
                        view.scaleX = original.scaleX
                        view.scaleY = original.scaleY
                    }
                    view.animate()
                        .scaleX(original.scaleX * focusScale)
                        .scaleY(original.scaleY * focusScale)
                        .translationZ(original.translationZ + if (preferredScale > 1f) dp(view, FOCUS_LIFT_DP) else 0f)
                        .setDuration(FOCUS_ANIMATION_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    lastScale = focusScale
                }
            }
            true
        }
        val observer = view.viewTreeObserver
        drawListeners[view] = FocusDrawing(observer, listener)
        observer.addOnPreDrawListener(listener)
        view.overlay.add(ring)
        view.post {
            if (!view.hasFocus() || rings[view] !== ring) return@post
            // Ask the viewport to reveal the focus margin, without changing its
            // padding or triggering a different layout when selection moves.
            val extra = if (preferredScale > 1f) dp(view, 8f) else 0f
            val marginX = kotlin.math.ceil(view.width * (preferredScale - 1f) / 2f + extra).toInt()
            val marginY = kotlin.math.ceil(view.height * (preferredScale - 1f) / 2f + extra).toInt()
            view.requestRectangleOnScreen(
                Rect(-marginX, -marginY, view.width + marginX, view.height + marginY), true
            )
            view.invalidate()
        }
    }

    private fun removeHighlight(view: View?) {
        if (view == null) return
        rings.remove(view)?.let(view.overlay::remove)
        drawListeners.remove(view)?.let { drawing ->
            if (drawing.observer.isAlive) drawing.observer.removeOnPreDrawListener(drawing.listener)
        }
        backgrounds.remove(view)?.let { background ->
            if (view.background === background.managed) setBackgroundPreservingPadding(view, background.original)
        }
        val original = originalVisuals.remove(view) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled = original.defaultFocusHighlight
        view.animate().cancel()
        view.scaleX = original.scaleX
        view.scaleY = original.scaleY
        view.translationZ = original.translationZ
        view.elevation = original.elevation
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    /** Full-width form screens opt into a stationary, inset focus ring via their root tag. */
    internal fun preferredFocusScale(view: View): Float {
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.tag == NO_FOCUS_ZOOM_TAG) return 1f
            ancestor = ancestor.parent as? View
        }
        val isTv = (view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        return if (isTv) TV_FOCUS_SCALE else FOCUS_SCALE
    }

    /** Largest centered zoom that fits every clipping ancestor, in its coordinates. */
    internal fun fittingScale(view: View, preferredScale: Float): Float {
        if (view.width == 0 || view.height == 0) return 1f
        val original = originalVisuals[view]
        val bounds = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val pivot = floatArrayOf(view.pivotX, view.pivotY)
        val matrix = Matrix().apply {
            // Ignore the current focus animation when measuring available room.
            setScale(original?.scaleX ?: view.scaleX, original?.scaleY ?: view.scaleY,
                view.pivotX, view.pivotY)
            postRotate(view.rotation, view.pivotX, view.pivotY)
        }
        matrix.mapRect(bounds)
        matrix.mapPoints(pivot)
        var child = view
        var parent = child.parent as? ViewGroup
        var scale = preferredScale
        while (parent != null) {
            val dx = child.left + child.translationX - parent.scrollX
            val dy = child.top + child.translationY - parent.scrollY
            bounds.offset(dx, dy)
            pivot[0] += dx
            pivot[1] += dy
            val padded = parent.clipToPadding && (parent.paddingLeft != 0 ||
                parent.paddingTop != 0 || parent.paddingRight != 0 || parent.paddingBottom != 0)
            if (parent.clipChildren || padded || parent.parent !is ViewGroup) {
                scale = limitScale(bounds, pivot, RectF(
                    if (padded) parent.paddingLeft.toFloat() else 0f,
                    if (padded) parent.paddingTop.toFloat() else 0f,
                    parent.width - if (padded) parent.paddingRight.toFloat() else 0f,
                    parent.height - if (padded) parent.paddingBottom.toFloat() else 0f
                ), scale)
            }
            parent.clipBounds?.let { scale = limitScale(bounds, pivot, RectF(it), scale) }
            if (parent.clipToOutline) {
                scale = limitScale(bounds, pivot,
                    RectF(0f, 0f, parent.width.toFloat(), parent.height.toFloat()), scale)
            }
            parent.matrix.mapRect(bounds)
            parent.matrix.mapPoints(pivot)
            child = parent
            parent = parent.parent as? ViewGroup
        }
        return scale.coerceIn(1f, preferredScale)
    }

    private fun limitScale(bounds: RectF, pivot: FloatArray, clip: RectF, preferred: Float): Float {
        var scale = preferred
        val extents = floatArrayOf(pivot[0] - bounds.left, bounds.right - pivot[0],
            pivot[1] - bounds.top, bounds.bottom - pivot[1])
        val room = floatArrayOf(pivot[0] - clip.left, clip.right - pivot[0],
            pivot[1] - clip.top, clip.bottom - pivot[1])
        for (i in extents.indices) {
            if (extents[i] > 0f) scale = minOf(scale, room[i] / extents[i])
        }
        return scale
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

    private fun prepareBackground(view: View) {
        val background = view.background
        if (backgrounds[view]?.managed === background && backgrounds.containsKey(view)) return
        val copy = background?.constantState?.newDrawable(view.resources)?.mutate()
        if (copy != null) {
            copy.state = view.drawableState
            FocusRingDrawable.removeBackgroundStroke(copy)
            setBackgroundPreservingPadding(view, copy)
        }
        backgrounds[view] = BackgroundVisuals(background, copy ?: background)
    }

    private fun setBackgroundPreservingPadding(view: View, background: Drawable?) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        val relative = view.isPaddingRelative
        val start = view.paddingStart
        val end = view.paddingEnd
        view.background = background
        if (relative) view.setPaddingRelative(start, top, end, bottom)
        else view.setPadding(left, top, right, bottom)
    }

    private const val NO_FOCUS_ZOOM_TAG = "focus_without_zoom"
    private const val FOCUS_SCALE = 1.055f
    private const val TV_FOCUS_SCALE = 1.075f
    private const val FOCUS_LIFT_DP = 8f
    private const val FOCUS_ANIMATION_MS = 110L
}
