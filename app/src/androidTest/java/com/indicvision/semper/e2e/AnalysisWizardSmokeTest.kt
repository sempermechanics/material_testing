@file:Suppress("MagicNumber")

package com.indicvision.semper.e2e

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI smoke: open the analysis screen and assert the wizard chrome
 * is alive. This is not a full E2E (no fixture pick / compute); CI must not
 * treat it as one.
 */
@RunWith(AndroidJUnit4::class)
class AnalysisWizardSmokeTest {

    @get:Rule
    val scenarioRule = ActivityScenarioRule(StaticAnalysisActivity::class.java)

    @Test
    fun analysisActivity_showsWizardNextWithoutCrashing() {
        onView(withId(R.id.btnNext)).check(matches(isDisplayed()))
    }

    @Test
    fun analysisActivity_showsInstructionChrome() {
        onView(withId(R.id.tvInstruction)).check(matches(isDisplayed()))
    }

    @Test
    fun analysisActivity_showsWizardBackControl() {
        onView(withId(R.id.btnBack)).check(matches(isDisplayed()))
    }

    @Test
    fun analysisActivity_showsComputeControl() {
        onView(withId(R.id.btnCalculateFullField)).check(matches(isDisplayed()))
    }

    @Test
    fun analysisActivity_showsToolbar() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }
}
