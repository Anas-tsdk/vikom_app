package com.example.tv_caller_app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.Espresso.pressBack
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
class MainActivityTest {

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

    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))

    @Test
    fun headerIsVisible() {
        launch().use { onView(withId(R.id.header_bar)).check(matches(isDisplayed())) }
    }

    @Test
    fun clockTimeIsVisible() {
        launch().use { onView(withId(R.id.txt_clock_time)).check(matches(isDisplayed())) }
    }

    @Test
    fun clockDateIsVisible() {
        launch().use { onView(withId(R.id.txt_clock_date)).check(matches(isDisplayed())) }
    }

    @Test
    fun aiAssistantButtonIsVisibleAndFocusable() {
        launch().use {
            onView(withId(R.id.btn_ai_assistant))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }

    @Test
    fun profileButtonIsVisibleAndFocusable() {
        launch().use {
            onView(withId(R.id.btn_profile))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }

    @Test
    fun settingsButtonIsVisibleAndFocusable() {
        launch().use {
            onView(withId(R.id.btn_settings))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }

    @Test
    fun homeHeroAndTilesAreVisible() {
        launch().use {
            onView(withId(R.id.hero_card))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
            onView(withId(R.id.tile_appointments)).check(matches(isDisplayed()))
            onView(withId(R.id.tile_contacts)).check(matches(isDisplayed()))
            onView(withId(R.id.tile_care_team)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun callTileOpensExistingQuickDialAndBackReturnsHome() {
        launch().use {
            onView(withId(R.id.tile_contacts)).perform(click())
            onView(withId(R.id.main_grid)).check(matches(isDisplayed()))
            onView(withId(R.id.tab_quick_dial)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.hero_card)).check(matches(isDisplayed()))
        }
    }
}
