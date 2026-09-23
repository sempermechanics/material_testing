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

    /**
     * Same for a refused bundle download. Anything that is not a licence
     * refusal keeps the generic connection hint — a 5xx or a dropped link is
     * still the common case there.
     */
    fun downloadMessage(context: Context, detailOrMessage: String?): String {
        val detail = detailOrMessage?.let { ApiErrors.detailOf(it) }.orEmpty()
        return when {
            ApiErrors.hasCode(detail, ApiErrors.FEATURE_NOT_LICENSED) ->
                context.getString(R.string.download_not_licensed)
            ApiErrors.hasCode(detail, ApiErrors.LICENSE_DEVICE_MISMATCH) ->
                context.getString(R.string.restore_device_mismatch)
            else -> context.getString(R.string.download_analysis_failed)
        }
    }
}
