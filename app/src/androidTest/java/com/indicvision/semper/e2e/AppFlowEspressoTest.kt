@file:Suppress("MagicNumber")

package com.indicvision.semper.e2e

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indicvision.semper.ui.auth.SplashActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFlowEspressoTest {

    @get:Rule
    val scenarioRule = ActivityScenarioRule(SplashActivity::class.java)

    @Test
    fun splashActivity_launches() {
        onView(withId(android.R.id.content)).check(matches(isDisplayed()))
    }
}
