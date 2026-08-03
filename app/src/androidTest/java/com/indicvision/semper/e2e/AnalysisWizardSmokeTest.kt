@file:Suppress("MagicNumber")

package com.indicvision.semper.e2e

import android.view.View
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI smoke: open the analysis screen and assert cold-start wizard
 * chrome. Step 1 shows Next + toolbar; Back / Compute / settings instruction
 * stay in the hierarchy as [View.GONE]. Not a full E2E (no fixture pick).
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
    fun analysisActivity_showsToolbar() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }

    @Test
    fun analysisActivity_keepsInstructionOnSettingsPage() {
        scenarioRule.scenario.onActivity { activity ->
            val instruction = activity.findViewById<TextView>(R.id.tvInstruction)
            assertNotNull(instruction)
            // Settings page is not showing on step 1, so the row is not shown.
            assertEquals(false, instruction.isShown)
        }
    }

    @Test
    fun analysisActivity_hidesWizardBackOnFirstStep() {
        scenarioRule.scenario.onActivity { activity ->
            val back = activity.findViewById<View>(R.id.btnBack)
            assertNotNull(back)
            assertEquals(View.GONE, back.visibility)
        }
    }

    @Test
    fun analysisActivity_hidesComputeOnFirstStep() {
        scenarioRule.scenario.onActivity { activity ->
            val compute = activity.findViewById<View>(R.id.btnCalculateFullField)
            assertNotNull(compute)
            assertEquals(View.GONE, compute.visibility)
        }
    }
}
