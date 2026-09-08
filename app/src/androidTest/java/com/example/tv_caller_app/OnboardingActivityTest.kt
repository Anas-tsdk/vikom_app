package com.example.tv_caller_app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isFocusable
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.tv_caller_app.ui.activities.OnboardingActivity
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import org.hamcrest.Matchers.`is`
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class OnboardingActivityTest {

    companion object {
        @BeforeClass @JvmStatic
        fun enableAccessibilityChecks() {
            AccessibilityChecks.enable()
                .setRunChecksFromRootView(true)
                .setSuppressingResultMatcher(
                    AccessibilityCheckResultUtils.matchesCheckNames(`is`("TouchTargetSizeCheck"))
                )
        }
    }

    @get:Rule
    val activityRule = ActivityScenarioRule(OnboardingActivity::class.java)

    @Test
    fun titleIsVisible() {
        onView(withId(R.id.onboarding_title)).check(matches(isDisplayed()))
    }

    @Test
    fun descriptionIsVisible() {
        onView(withId(R.id.onboarding_description)).check(matches(isDisplayed()))
    }

    @Test
    fun nextButtonIsVisibleAndFocusable() {
        onView(withId(R.id.btn_onboarding_next))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    fun nextButtonAdvancesPage() {
        onView(withId(R.id.btn_onboarding_next)).perform(click())
        onView(withId(R.id.btn_onboarding_prev)).check(matches(isDisplayed()))
    }

    @Test
    fun dotsIndicatorIsVisible() {
        onView(withId(R.id.onboarding_dots)).check(matches(isDisplayed()))
    }
}
