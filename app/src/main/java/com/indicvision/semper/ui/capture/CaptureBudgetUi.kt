package com.indicvision.semper.ui.capture

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R

/** Shared budget-fail dialog for setup and session activities. */
object CaptureBudgetUi {

    fun showFailDialog(
        activity: Activity,
        check: CaptureBudget.Check,
        positiveButtonRes: Int = android.R.string.ok,
        onPositive: (() -> Unit)? = null,
    ) {
        val msg = when {
            !check.ramOk && !check.storageOk -> R.string.capture_budget_fail_both
            !check.ramOk -> R.string.capture_budget_fail_ram
            else -> R.string.capture_budget_fail_storage
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.capture_budget_fail_title)
            .setMessage(msg)
            .setPositiveButton(positiveButtonRes) { _, _ -> onPositive?.invoke() }
            .show()
    }
}
