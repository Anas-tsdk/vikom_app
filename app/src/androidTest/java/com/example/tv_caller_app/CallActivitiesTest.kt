package com.example.tv_caller_app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isFocusable
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tv_caller_app.calling.CallIntentExtras
import com.example.tv_caller_app.ui.activities.InCallActivity
import com.example.tv_caller_app.ui.activities.IncomingCallActivity
import com.example.tv_caller_app.ui.activities.OutgoingCallActivity
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class CallActivitiesTest {

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
    fun initCallViewModel() {
        (context.applicationContext as TVCallerApplication).initializeCallViewModel()
    }


    private fun launchIncoming() = ActivityScenario.launch<IncomingCallActivity>(
        Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(CallIntentExtras.EXTRA_CALLER_ID, "test_caller")
            putExtra(CallIntentExtras.EXTRA_CALLER_NAME, "Test User")
            putExtra(CallIntentExtras.EXTRA_CALL_ID, "test_call_123")
        }
    )

    @Test
    fun incoming_callerNameIsVisible() {
        launchIncoming().use { onView(withId(R.id.txt_caller_name)).check(matches(isDisplayed())) }
    }

    @Test
    fun incoming_callerAvatarIsVisible() {
        launchIncoming().use { onView(withId(R.id.img_caller_avatar)).check(matches(isDisplayed())) }
    }

    @Test
    fun incoming_callStatusIsVisible() {
        launchIncoming().use { onView(withId(R.id.txt_call_status)).check(matches(isDisplayed())) }
    }

    @Test
    fun incoming_answerButtonIsVisibleAndFocusable() {
        launchIncoming().use {
            onView(withId(R.id.btn_answer_call))
                .check(matches(isDisplayed()))
                .check(matches(isEnabled()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }

    @Test
    fun incoming_rejectButtonIsVisibleAndFocusable() {
        launchIncoming().use {
            onView(withId(R.id.btn_reject_call))
                .check(matches(isDisplayed()))
                .check(matches(isEnabled()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }


    private fun launchOutgoing() = ActivityScenario.launch<OutgoingCallActivity>(
        Intent(context, OutgoingCallActivity::class.java).apply {
            putExtra(OutgoingCallActivity.EXTRA_CONTACT_ID, "test_contact")
            putExtra(OutgoingCallActivity.EXTRA_CONTACT_NAME, "Test User")
            putExtra(OutgoingCallActivity.EXTRA_CALL_ID, "test_call_123")
        }
    )

    @Test
    fun outgoing_contactNameIsVisible() {
        launchOutgoing().use { onView(withId(R.id.txt_contact_name)).check(matches(isDisplayed())) }
    }

    @Test
    fun outgoing_callStatusIsVisible() {
        launchOutgoing().use { onView(withId(R.id.txt_call_status)).check(matches(isDisplayed())) }
    }

    @Test
    fun outgoing_hangUpButtonIsVisibleAndFocusable() {
        launchOutgoing().use {
            onView(withId(R.id.btn_hang_up))
                .check(matches(isDisplayed()))
                .check(matches(isEnabled()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }


    private fun launchInCall() = ActivityScenario.launch<InCallActivity>(
        Intent(context, InCallActivity::class.java).apply {
            putExtra(InCallActivity.EXTRA_CONTACT_NAME, "Test User")
            putExtra(InCallActivity.EXTRA_CALL_ID, "test_call_123")
        }
    )

    @Test
    fun inCall_contactNameIsVisible() {
        launchInCall().use { onView(withId(R.id.txt_contact_name)).check(matches(isDisplayed())) }
    }

    @Test
    fun inCall_callTimerIsVisible() {
        launchInCall().use { onView(withId(R.id.txt_call_timer)).check(matches(isDisplayed())) }
    }

    @Test
    fun inCall_hangUpButtonIsVisibleAndFocusable() {
        launchInCall().use {
            onView(withId(R.id.btn_hang_up))
                .check(matches(isDisplayed()))
                .check(matches(isEnabled()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }

    @Test
    fun inCall_muteButtonIsVisibleAndFocusable() {
        launchInCall().use {
            onView(withId(R.id.btn_mute))
                .check(matches(isDisplayed()))
                .check(matches(isEnabled()))
                .check(matches(isFocusable()))
                .perform(click())
        }
    }

    @Test
    fun incoming_videoAnswerButtonIsVisibleAndFocusable() {
        launchIncoming().use {
            onView(withId(R.id.btn_answer_video_call))
                .check(matches(isDisplayed()))
                .check(matches(isEnabled()))
                .check(matches(isFocusable()))
        }
    }
}
