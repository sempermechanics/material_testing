package com.indicvision.semper.ui.common

import android.app.Activity
import android.content.Context
import android.view.ViewGroup

/** Where a [CrispToast] pill sits. */
enum class ToastPlacement {
    /** Bottom-centre, clear of the bottom chrome: the default pill. */
    BOTTOM,

    /** Top-centre, clear of the top chrome (the media picker's tips). */
    TOP,

    /** The larger pill, centred on its root (the picker's gated hint over its scrim). */
    CENTER_PROMINENT,
}

/** How long a [CrispToast] pill stays up. */
sealed interface ToastHold {
    /** The pill's own short time (2 s). */
    data object Short : ToastHold

    /** The pill's own long time (3.5 s). */
    data object Long : ToastHold

    /** Exactly [ms]. */
    data class Millis(val ms: kotlin.Long) : ToastHold
}

/**
 * One call for the three pills [CrispToast] already draws: [CrispToast.show]
 * (bottom, or `fromTop`) and [CrispToast.showProminent].
 *
 * The prominent pill has no hold of its own (its one caller passes a time), so
 * [ToastHold.Short] / [ToastHold.Long] there mean the other pills' times.
 * With no [overlayRoot] the pill sits on the Activity's content, as before;
 * a [context] that is not a live Activity shows nothing.
 */
fun CrispToast.show(
    context: Context,
    message: CharSequence,
    placement: ToastPlacement,
    overlayRoot: ViewGroup? = null,
    hold: ToastHold = ToastHold.Short,
) {
    when (placement) {
        ToastPlacement.BOTTOM, ToastPlacement.TOP -> {
            val fromTop = placement == ToastPlacement.TOP
            when (hold) {
                is ToastHold.Millis -> show(context, message, overlayRoot, fromTop, hold.ms)
                else -> show(
                    context,
                    message,
                    long = hold == ToastHold.Long,
                    overlayRoot = overlayRoot,
                    fromTop = fromTop,
                )
            }
        }
        ToastPlacement.CENTER_PROMINENT -> {
            val root = overlayRoot
                ?: (context as? Activity)?.findViewById(android.R.id.content)
                ?: return
            showProminent(context, message, root, holdMs(hold))
        }
    }
}

/** [hold] in ms; Short and Long are [CrispToast]'s private `SHORT_MS` / `LONG_MS`. */
internal fun holdMs(hold: ToastHold): Long = when (hold) {
    ToastHold.Short -> CRISP_SHORT_MS
    ToastHold.Long -> CRISP_LONG_MS
    is ToastHold.Millis -> hold.ms
}

private const val CRISP_SHORT_MS = 2000L
private const val CRISP_LONG_MS = 3500L
