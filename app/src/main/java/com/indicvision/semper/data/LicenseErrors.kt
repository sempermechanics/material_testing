package com.indicvision.semper.data

import android.content.Context
import com.indicvision.semper.R
import com.indicvision.semper.data.net.ApiErrors

/**
 * Maps backend licence/feature [detail] codes to user-facing restore messages.
 */
object LicenseErrors {

    fun restoreMessage(context: Context, detailOrMessage: String?): String {
        val detail = detailOrMessage?.let { ApiErrors.detailOf(it) }.orEmpty()
        return when {
            ApiErrors.hasCode(detail, ApiErrors.LICENSE_DEVICE_MISMATCH) ->
                context.getString(R.string.restore_device_mismatch)
            ApiErrors.hasCode(detail, ApiErrors.FEATURE_NOT_LICENSED) ->
                context.getString(R.string.restore_not_licensed)
            detail.isBlank() -> context.getString(R.string.restore_failed_generic)
            else -> context.getString(R.string.restore_failed_fmt, detail)
        }
    }
}
