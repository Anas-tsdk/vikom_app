package com.example.tv_caller_app.ui.activities

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import kotlinx.coroutines.launch

class OnboardingActivity : FragmentActivity() {

    companion object {
        fun createIntent(context: Context) = Intent(context, OnboardingActivity::class.java)
    }

    private data class Page(val iconRes: Int, val title: String, val description: String)

    private val pages by lazy {
        listOf(
            Page(
                iconRes = R.drawable.tv_caller_logo,
                title = getString(R.string.onboarding_page1_title),
                description = getString(R.string.onboarding_page1_desc)
            ),
            Page(
                iconRes = R.drawable.ic_star_filled,
                title = getString(R.string.onboarding_page2_title),
                description = getString(R.string.onboarding_page2_desc)
            ),
            Page(
                iconRes = R.drawable.onboarding_search,
                title = getString(R.string.onboarding_page3_title),
                description = getString(R.string.onboarding_page3_desc)
            ),
            Page(
                iconRes = R.drawable.onboarding_contact,
                title = getString(R.string.onboarding_page4_title),
                description = getString(R.string.onboarding_page4_desc)
            ),
            Page(
                iconRes = R.drawable.ic_call,
                title = getString(R.string.onboarding_page5_title),
                description = getString(R.string.onboarding_page5_desc)
            )
        )
    }

    private var currentPage = 0
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var dotsContainer: LinearLayout
    private lateinit var dots: Array<View>
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        icon = findViewById(R.id.onboarding_icon)
        title = findViewById(R.id.onboarding_title)
        description = findViewById(R.id.onboarding_description)
        btnPrev = findViewById(R.id.btn_onboarding_prev)
        btnNext = findViewById(R.id.btn_onboarding_next)
        dotsContainer = findViewById(R.id.onboarding_dots)
        content = findViewById(R.id.onboarding_content)

        buildDots()
        showPage(0, animate = false)
        btnNext.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage > 0) navigateTo(currentPage - 1)
            }
        })

        btnPrev.setOnClickListener { navigateTo(currentPage - 1) }
        btnNext.setOnClickListener {
            if (currentPage < pages.size - 1) {
                navigateTo(currentPage + 1)
            } else {
                val repo = (application as TVCallerApplication).getProfileRepository()
                lifecycleScope.launch { repo.markOnboardingComplete() }
                finish()
            }
        }

        btnPrev.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                btnNext.requestFocus(); true
            } else false
        }
        btnNext.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                && btnPrev.visibility == View.VISIBLE) {
                btnPrev.requestFocus(); true
            } else false
        }
    }

    private fun navigateTo(page: Int) {
        if (page < 0 || page >= pages.size) return
        showPage(page, animate = true)
        if (page == pages.size - 1) btnNext.requestFocus()
        else if (page == 0) btnNext.requestFocus()
        else btnNext.requestFocus()
    }

    private fun buildDots() {
        val density = resources.displayMetrics.density
        val size = (12 * density).toInt()
        val margin = (8 * density).toInt()
        dots = Array(pages.size) { View(this) }
        dots.forEach { dot ->
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
            dotsContainer.addView(dot)
        }
    }

    private fun showPage(index: Int, animate: Boolean) {
        currentPage = index
        val page = pages[index]

        if (animate) {
            val fadeOut = ObjectAnimator.ofFloat(content, "alpha", 1f, 0f).apply { duration = 150 }
            fadeOut.start()
            content.postDelayed({
                bindPage(page, index)
                ObjectAnimator.ofFloat(content, "alpha", 0f, 1f).apply {
                    duration = 200
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }, 150)
        } else {
            bindPage(page, index)
        }
    }

    private fun bindPage(page: Page, index: Int) {
        icon.setImageResource(page.iconRes)
        title.text = page.title
        description.text = page.description

        dots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i == index) R.drawable.onboarding_dot_active
                else R.drawable.onboarding_dot_inactive
            )
        }

        btnPrev.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        btnNext.text = getString(
            if (index == pages.size - 1) R.string.onboarding_finish else R.string.onboarding_next
        )
    }

}
