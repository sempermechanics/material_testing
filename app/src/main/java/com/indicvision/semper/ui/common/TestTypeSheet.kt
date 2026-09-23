@file:SuppressLint("InflateParams")

package com.indicvision.semper.ui.common

import android.annotation.SuppressLint
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.indicvision.semper.R
import com.indicvision.semper.data.TestType

/**
 * The first question a new analysis asks: which mechanical test the frames
 * come from. Shown from Home's **+** before the media picker, so the wizard
 * already knows whether to ask for machine loads by the time it opens.
 *
 * A sheet rather than a wizard step: the wizard's chrome indexes a fixed page
 * list and counts its steps in the toolbar, and every one of those would shift
 * for a page that holds one tap.
 */
object TestTypeSheet {

    fun show(activity: AppCompatActivity, onPicked: (TestType) -> Unit) {
        val sheet = BottomSheetDialog(activity)
        val root = activity.layoutInflater.inflate(R.layout.sheet_test_type, null)
        sheet.setContentView(root)
        val rows = listOf(
            R.id.btnTestTensile to TestType.TENSILE,
            R.id.btnTestBending to TestType.BENDING,
        )
        rows.forEach { (id, type) ->
            root.findViewById<View>(id).setOnClickListener {
                sheet.dismiss()
                onPicked(type)
            }
        }
        sheet.show()
    }

    /** The user-facing name of a type, for the ⓘ sheet and reports. */
    fun label(activity: AppCompatActivity, type: TestType): String = activity.getString(labelRes(type))

    fun labelRes(type: TestType): Int = when (type) {
        TestType.TENSILE -> R.string.test_type_tensile
        TestType.BENDING -> R.string.test_type_bending
    }
}
