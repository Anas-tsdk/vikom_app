package com.example.tv_caller_app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import org.hamcrest.Matchers.`is`
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isFocusable
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.tv_caller_app.ui.activities.AuthActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AuthActivityTest {

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
    val activityRule = ActivityScenarioRule(AuthActivity::class.java)

    @Test
    fun containerIsVisible() {
        onView(withId(R.id.auth_fragment_container)).check(matches(isDisplayed()))
    }

    @Test
    fun emailInputIsVisible() {
        onView(withId(R.id.email_input)).check(matches(isDisplayed()))
    }

    @Test
    fun passwordInputIsVisible() {
        onView(withId(R.id.password_input)).check(matches(isDisplayed()))
    }

    @Test
    fun logoIsVisible() {
        onView(withId(R.id.login_logo)).check(matches(isDisplayed()))
    }

    @Test
    fun loginButtonIsVisibleAndFocusable() {
        onView(withId(R.id.login_button))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }


    @Test
    fun registerScreen_usernameInputIsVisible() {
        onView(withId(R.id.register_button)).perform(click())
        onView(withId(R.id.username_input)).check(matches(isDisplayed()))
    }

    @Test
    fun registerScreen_emailInputIsVisible() {
        onView(withId(R.id.register_button)).perform(click())
        onView(withId(R.id.email_input)).check(matches(isDisplayed()))
    }

    @Test
    fun registerScreen_passwordInputIsVisible() {
        onView(withId(R.id.register_button)).perform(click())
        onView(withId(R.id.password_input)).check(matches(isDisplayed()))
    }

    @Test
    fun registerScreen_confirmPasswordInputIsVisible() {
        onView(withId(R.id.register_button)).perform(click())
        onView(withId(R.id.confirm_password_input)).check(matches(isDisplayed()))
    }

    @Test
    fun registerScreen_registerButtonIsVisibleAndFocusable() {
        onView(withId(R.id.register_button)).perform(click())
        onView(withId(R.id.register_button))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    fun registerScreen_backToLoginIsVisibleAndFocusable() {
        onView(withId(R.id.register_button)).perform(click())
        onView(withId(R.id.back_to_login_button))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
            .perform(click())
    }


    @Test
    fun forgotPasswordLinkIsVisibleAndFocusable() {
        onView(withId(R.id.forgot_password_link))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    fun resetFlow_otpInputIsVisible() {
        onView(withId(R.id.forgot_password_link)).perform(click())
        onView(withId(R.id.otp_code_input)).check(matches(isDisplayed()))
    }

    @Test
    fun resetFlow_verifyButtonIsVisibleAndFocusable() {
        onView(withId(R.id.forgot_password_link)).perform(click())
        onView(withId(R.id.btn_verify_code))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
    }

    @Test
    fun resetFlow_otpBackButtonIsVisibleAndFocusable() {
        onView(withId(R.id.forgot_password_link)).perform(click())
        onView(withId(R.id.btn_otp_back))
            .check(matches(isDisplayed()))
            .check(matches(isFocusable()))
            .perform(click())
    }
}
