package com.example.tv_caller_app

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
class QuickDialFragmentTest {

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

    private fun launch() = ActivityScenario.launch<MainActivity>(
        android.content.Intent(context, MainActivity::class.java)
    )

    private fun openQuickDial() {
        onView(withId(R.id.tile_contacts)).perform(click())
    }

    @Test
    fun quickDialTabIsVisibleAndFocusable() {
        launch().use {
            openQuickDial()
            onView(withId(R.id.tab_quick_dial))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }

    @Test
    fun contactsTabIsVisibleAndFocusable() {
        launch().use {
            openQuickDial()
            onView(withId(R.id.tab_contacts))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }

    @Test
    fun mainGridIsVisible() {
        launch().use {
            openQuickDial()
            onView(withId(R.id.main_grid)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun statusHeaderIsVisible() {
        launch().use {
            openQuickDial()
            onView(withId(R.id.txt_status)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun contactsTabNavigatesToAllContacts() {
        launch().use {
            openQuickDial()
            onView(withId(R.id.tab_contacts)).perform(click())
            onView(withId(R.id.search_input)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun manageFavoritesButtonIsVisibleAndFocusable() {
        launch().use {
            openQuickDial()
            onView(withId(R.id.btn_manage_favorites))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }
}
