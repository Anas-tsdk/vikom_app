package com.example.tv_caller_app.ui.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Keeps chips and actions at their natural width, wrapping whole items as needed. */
class WrappingRowLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private val positions = mutableMapOf<View, Rect>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        positions.clear()
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        var x = 0
        var y = 0
        var rowHeight = 0
        var contentWidth = 0
        var childState = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.marginStart + lp.marginEnd
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && childWidth > available - x) {
                y += rowHeight
                x = 0
                rowHeight = 0
            }
            positions[child] = Rect(x + lp.marginStart, y + lp.topMargin,
                x + lp.marginStart + child.measuredWidth, y + lp.topMargin + child.measuredHeight)
            x += childWidth
            contentWidth = maxOf(contentWidth, x)
            rowHeight = maxOf(rowHeight, childHeight)
            childState = combineMeasuredStates(childState, child.measuredState)
        }
        setMeasuredDimension(
            resolveSizeAndState(maxOf(suggestedMinimumWidth, contentWidth + paddingLeft + paddingRight),
                widthMeasureSpec, childState),
            resolveSizeAndState(maxOf(suggestedMinimumHeight, y + rowHeight + paddingTop + paddingBottom),
                heightMeasureSpec, childState shl MEASURED_HEIGHT_STATE_SHIFT)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((child, rect) in positions) {
            if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                child.layout(width - paddingRight - rect.right, paddingTop + rect.top,
                    width - paddingRight - rect.left, paddingTop + rect.bottom)
            } else {
                child.layout(paddingLeft + rect.left, paddingTop + rect.top,
                    paddingLeft + rect.right, paddingTop + rect.bottom)
            }
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams
}
