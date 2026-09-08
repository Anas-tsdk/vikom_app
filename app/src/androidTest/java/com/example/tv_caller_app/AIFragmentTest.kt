package com.example.tv_caller_app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isFocusable
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.ui.activities.MainActivity
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import org.hamcrest.Matchers.`is`
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AIFragmentTest {

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

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun injectFakeSession() {
        SessionManager.getInstance(context).apply {
            saveSession("test-uid", "fake-token", "fake-refresh", "test@test.com", true)
            saveUsername("Testbruker")
        }
    }

    @After
    fun clearFakeSession() {
        SessionManager.getInstance(context).clearSession()
    }

    private fun waitForView(id: Int, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (e: Exception) {
                Thread.sleep(150)
            }
        }
    }

    private fun launch() = ActivityScenario.launch<MainActivity>(
        Intent(context, MainActivity::class.java)
    )

    private fun navigateToAI() {
        onView(withId(R.id.btn_ai_assistant)).perform(click())
        waitForView(R.id.txt_ai_greeting)
    }

    @Test
    fun greetingIsVisible() {
        launch().use {
            navigateToAI()
            onView(withId(R.id.txt_ai_greeting)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun topicMedicineIsVisibleAndFocusable() {
        launch().use {
            navigateToAI()
            onView(withId(R.id.topic_medicine))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }

    @Test
    fun topicAppointmentsIsVisibleAndFocusable() {
        launch().use {
            navigateToAI()
            onView(withId(R.id.topic_appointments))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }

    @Test
    fun topicHelpIsVisibleAndFocusable() {
        launch().use {
            navigateToAI()
            onView(withId(R.id.topic_help))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }

    @Test
    fun topicDailyIsVisibleAndFocusable() {
        launch().use {
            navigateToAI()
            onView(withId(R.id.topic_daily))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }

    @Test
    fun backButtonIsVisibleAndFocusable() {
        launch().use {
            navigateToAI()
            onView(withId(R.id.btn_ai_confirm))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }
}
