package com.sempermechanics.semper.ui.common

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.mechanical.TestType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * "Which test?" offers Semper's own analysis, 2D DIC, first, then the two lab
 * tests, and a row picks its test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TestTypeSheetTest {

    @Test
    fun `2D DIC comes first, then tensile and bending`() {
        assertEquals(
            listOf(TestType.DIC_2D, TestType.TENSILE, TestType.BENDING),
            TestTypeSheet.ORDER.map { it.second },
        )
    }

    @Test
    fun `the sheet's rows sit in that order, and each picks its test`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_Semper)
        controller.setup().use {
            val activity = controller.get()
            val picked = mutableListOf<TestType>()
            TestTypeSheet.show(activity) { picked += it }
            val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
            val rows = sheet.findViewById<View>(R.id.btnTestDic2d)!!.parent as ViewGroup

            val shown = (0 until rows.childCount).map { rows.getChildAt(it).id }
            assertEquals(TestTypeSheet.ORDER.map { it.first }, shown)

            rows.getChildAt(0).performClick()
            assertEquals(listOf(TestType.DIC_2D), picked)
        }
    }
}
