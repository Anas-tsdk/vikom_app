package com.example.tv_caller_app.ui.views

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.tv_caller_app.R
import java.time.LocalTime
import java.util.Locale

/**
 * ViKom-owned 24-hour time wheel. It deliberately uses ordinary RecyclerViews
 * instead of Android's platform TimePicker, keeping the complete interaction in
 * the app while retaining the familiar alarm-clock wheel motion.
 */
class VikomTimeWheelPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val itemHeight = (58 * resources.displayMetrics.density).toInt()
    private var selectedHour = 0
    private var selectedMinute = 0

    private lateinit var hourWheel: Wheel
    private lateinit var minuteWheel: Wheel

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(createHeadings())

        val wheels = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        hourWheel = createWheel(24) { selectedHour = it }
        minuteWheel = createWheel(60) { selectedMinute = it }
        wheels.addView(hourWheel.frame)
        wheels.addView(TextView(context).apply {
            text = ":"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.vk_text_primary))
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(
                (32 * resources.displayMetrics.density).toInt(),
                itemHeight * 5
            )
        })
        wheels.addView(minuteWheel.frame)
        addView(wheels)
    }

    fun setTime(time: LocalTime) {
        selectedHour = time.hour
        selectedMinute = time.minute
        hourWheel.select(time.hour)
        minuteWheel.select(time.minute)
    }

    fun selectedTime(): LocalTime = LocalTime.of(selectedHour, selectedMinute)

    private fun createHeadings(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(wheelHeading(R.string.time_picker_hours))
        addView(Space(context).apply {
            layoutParams = LayoutParams((32 * resources.displayMetrics.density).toInt(), 1)
        })
        addView(wheelHeading(R.string.time_picker_minutes))
    }

    private fun wheelHeading(labelRes: Int): TextView = TextView(context).apply {
        setText(labelRes)
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.vk_text_muted))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun createWheel(valueCount: Int, onSelected: (Int) -> Unit): Wheel {
        val recycler = RecyclerView(context)
        val manager = LinearLayoutManager(context)
        val adapter = WheelAdapter(valueCount)
        recycler.layoutManager = manager
        recycler.adapter = adapter
        recycler.clipToPadding = false
        recycler.setPadding(0, itemHeight * 2, 0, itemHeight * 2)
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        recycler.isNestedScrollingEnabled = false
        recycler.contentDescription = context.getString(
            if (valueCount == 24) R.string.time_picker_hours else R.string.time_picker_minutes
        )

        val snapHelper = LinearSnapHelper().also { it.attachToRecyclerView(recycler) }
        val wheel = Wheel(recycler, manager, adapter, snapHelper, onSelected)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) wheel.commitSnappedValue()
            }
        })

        val frame = FrameLayout(context).apply {
            layoutParams = LayoutParams(0, itemHeight * 5, 1f)
            addView(recycler, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(View(context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = ContextCompat.getDrawable(context, R.drawable.time_wheel_selection)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                itemHeight,
                Gravity.CENTER
            ))
        }
        wheel.frame = frame
        return wheel
    }

    private inner class Wheel(
        val recycler: RecyclerView,
        val manager: LinearLayoutManager,
        val adapter: WheelAdapter,
        val snapHelper: LinearSnapHelper,
        val onSelected: (Int) -> Unit
    ) {
        lateinit var frame: FrameLayout

        fun select(value: Int) {
            adapter.selected = value
            recycler.post {
                // Position zero is measured from RecyclerView's padded start;
                // the two-row top padding therefore places this value precisely
                // inside the central selection band.
                manager.scrollToPositionWithOffset(value, 0)
                adapter.notifyDataSetChanged()
            }
        }

        fun commitSnappedValue() {
            val snapped = snapHelper.findSnapView(manager) ?: return
            val position = manager.getPosition(snapped)
            if (position == RecyclerView.NO_POSITION) return
            val previous = adapter.selected
            adapter.selected = position
            adapter.notifyItemChanged(previous)
            adapter.notifyItemChanged(position)
            onSelected(position)
            recycler.announceForAccessibility(String.format(Locale.ROOT, "%02d", position))
        }
    }

    private inner class WheelAdapter(private val count: Int) : RecyclerView.Adapter<WheelViewHolder>() {
        var selected: Int = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WheelViewHolder {
            val textView = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    itemHeight
                )
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setTextColor(ContextCompat.getColor(context, R.color.vk_text_muted))
            }
            return WheelViewHolder(textView)
        }

        override fun onBindViewHolder(holder: WheelViewHolder, position: Int) {
            val active = position == selected
            holder.text.text = String.format(Locale.ROOT, "%02d", position)
            holder.text.textSize = if (active) 32f else 24f
            holder.text.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.text.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.vk_primary else R.color.vk_text_muted
                )
            )
            holder.text.contentDescription = String.format(Locale.ROOT, "%02d", position)
            holder.text.setOnClickListener {
                (holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION })?.let {
                    // Smooth scrolling keeps a tap on a nearby value feeling like
                    // the same wheel interaction as a swipe.
                    val parentRecycler = holder.itemView.parent as? RecyclerView
                    parentRecycler?.smoothScrollBy(0, (it - selected) * itemHeight)
                }
            }
        }

        override fun getItemCount(): Int = count
    }

    private class WheelViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}
