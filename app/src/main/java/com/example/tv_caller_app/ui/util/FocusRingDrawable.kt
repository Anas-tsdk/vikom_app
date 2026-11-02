package com.example.tv_caller_app.ui.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable

/** One inset stroke following the actual background, including asymmetric corners. */
internal class FocusRingDrawable(private val density: Float, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        this.color = color
    }
    private val rect = RectF()
    private val path = Path()
    private var shape: GradientDrawable? = null

    fun followBackground(background: Drawable?) {
        shape = findShape(background)
    }

    override fun draw(canvas: Canvas) {
        val background = shape
        // InsetDrawable may place the painted button inside the View bounds.
        rect.set(background?.bounds?.takeUnless { it.isEmpty } ?: bounds)
        if (!rect.intersect(RectF(bounds))) return
        val inset = paint.strokeWidth / 2f
        rect.inset(inset, inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        if (background?.shape == GradientDrawable.OVAL) {
            canvas.drawOval(rect, paint)
        } else {
            val radii = background?.cornerRadii?.copyOf()
                ?: FloatArray(8) { background?.cornerRadius ?: 8f * density }
            for (i in radii.indices) radii[i] = (radii[i] - inset).coerceAtLeast(0f)
            path.reset()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        internal fun findShape(drawable: Drawable?): GradientDrawable? = when (drawable) {
            null -> null
            is GradientDrawable -> drawable
            is InsetDrawable -> findShape(drawable.drawable)
            is LayerDrawable -> (0 until drawable.numberOfLayers)
                .firstNotNullOfOrNull { findShape(drawable.getDrawable(it)) }
            else -> drawable.current.takeIf { it !== drawable }?.let(::findShape)
        }

        /** Only called on a private clone, so other buttons and unfocused states are preserved. */
        internal fun removeBackgroundStroke(drawable: Drawable?) {
            when (drawable) {
                null -> Unit
                is GradientDrawable -> drawable.setStroke(0, Color.TRANSPARENT)
                is InsetDrawable -> removeBackgroundStroke(drawable.drawable)
                is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) {
                    removeBackgroundStroke(drawable.getDrawable(i))
                }
                else -> drawable.current.takeIf { it !== drawable }?.let(::removeBackgroundStroke)
            }
        }
    }
}
