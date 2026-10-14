package com.example.tv_caller_app.ui.util

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import java.util.WeakHashMap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Uses the whole TV window, regardless of its resolution or density. */
object TvWindowManager : Application.ActivityLifecycleCallbacks {
    private val insetRoots = WeakHashMap<Activity, View>()

    /** Keeps controls inside the TV safe area and above the software keyboard. */
    fun installContentInsets(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: return
        if (insetRoots[activity] === root) return
        insetRoots[activity] = root
        val isTv = (activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val safeX = if (isTv) (view.width * 0.03f).toInt() else 0
            val safeY = if (isTv) (view.height * 0.03f).toInt() else 0
            view.setPadding(
                left + maxOf(safeX, system.left),
                top + maxOf(safeY, system.top),
                right + maxOf(safeX, system.right),
                bottom + maxOf(safeY, system.bottom, keyboard.bottom)
            )
            if (keyboard.bottom > 0) {
                view.post {
                    view.findFocus()?.let { focused ->
                        focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height), true)
                    }
                }
            }
            insets
        }
        root.addOnLayoutChangeListener { view, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) ViewCompat.requestApplyInsets(view)
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyFullScreen(activity: Activity) {
        val configuration = activity.resources.configuration
        if (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK !=
            Configuration.UI_MODE_TYPE_TELEVISION) return

        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = applyFullScreen(activity)
    override fun onActivityResumed(activity: Activity) {
        applyFullScreen(activity)
        if ((activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION) installContentInsets(activity)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        insetRoots.remove(activity)
    }
}
