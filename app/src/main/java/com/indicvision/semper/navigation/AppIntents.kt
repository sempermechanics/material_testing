package com.indicvision.semper.navigation

import android.content.Context
import android.content.Intent

/**
 * Intent factories for cross-layer navigation without `data`/`report` importing
 * concrete Activity classes under `ui`.
 */
object AppIntents {
    private const val SESSION_LIMIT =
        "com.indicvision.semper.ui.limit.SessionLimitActivity"

    fun sessionLimit(context: Context): Intent =
        Intent().setClassName(context, SESSION_LIMIT).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
}
