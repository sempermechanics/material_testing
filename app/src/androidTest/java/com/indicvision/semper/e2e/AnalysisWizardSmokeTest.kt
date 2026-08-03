@file:Suppress("MagicNumber")

package com.indicvision.semper.e2e

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI smoke: open the analysis screen and assert cold-start wizard
 * chrome. Step 1 hides Back / Compute and the settings-page instruction; those
 * are asserted GONE rather than displayed. Not a full E2E (no fixture pick).
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
    fun analysisActivity_keepsInstructionOnSettingsPage() {
        // tvInstruction lives on the settings page, which is GONE on step 1.
        onView(withId(R.id.tvInstruction))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun analysisActivity_hidesWizardBackOnFirstStep() {
        onView(withId(R.id.btnBack))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun analysisActivity_hidesComputeOnFirstStep() {
        onView(withId(R.id.btnCalculateFullField))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun analysisActivity_showsToolbar() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }
}
