package com.example.tv_caller_app

import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isFocusable
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.ui.activities.MainActivity
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.containsStringIgnoringCase
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class ContactDetailFragmentTest {

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
        Intent(context, MainActivity::class.java)
    )

    private fun focusAndClick() = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription() = "focus and click"
        override fun perform(uiController: UiController, view: View) {
            view.requestFocus()
            uiController.loopMainThreadUntilIdle()
            view.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun recyclerViewHasItems(id: Int): Boolean {
        var hasItems = false
        onView(withId(id)).check { view, _ ->
            hasItems = (view as? RecyclerView)?.adapter?.itemCount?.let { it > 0 } ?: false
        }
        return hasItems
    }

    private fun waitForRecyclerViewItems(id: Int, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (recyclerViewHasItems(id)) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun navigateViaContactsList() {
        onView(withId(R.id.tab_contacts)).perform(click())
        Assume.assumeTrue("No contacts in list", waitForRecyclerViewItems(R.id.contacts_list))
        onView(withId(R.id.contacts_list))
            .perform(RecyclerViewActions.actionOnItem<RecyclerView.ViewHolder>(
                withText(containsStringIgnoringCase("olav")), focusAndClick()
            ))
    }

    @Test
    fun contactNameIsVisible() {
        launch().use {
            navigateViaContactsList()
            onView(withId(R.id.contact_name)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun contactAvatarIsVisible() {
        launch().use {
            navigateViaContactsList()
            onView(withId(R.id.img_contact_avatar)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun contactOnlineStatusIsVisible() {
        launch().use {
            navigateViaContactsList()
            onView(withId(R.id.contact_online_status)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun callButtonIsVisibleAndFocusable() {
        launch().use {
            navigateViaContactsList()
            onView(withId(R.id.btn_call))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }

    @Test
    fun videoCallButtonIsVisibleAndFocusable() {
        launch().use {
            navigateViaContactsList()
            onView(withId(R.id.btn_video_call))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
        }
    }

    @Test
    fun backButtonIsVisibleAndFocusable() {
        launch().use {
            navigateViaContactsList()
            onView(withId(R.id.btn_go_back))
                .check(matches(isDisplayed()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }
}
