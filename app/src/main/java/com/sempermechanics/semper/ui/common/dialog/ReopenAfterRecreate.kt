package com.sempermechanics.semper.ui.common.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Brings a sheet or dialog back after a configuration change (rotation, font
 * scale, dark theme, a resize) rebuilds its Activity. A plain [Dialog] closed
 * with the old Activity and took a half-made choice with it (readiness
 * AE1-111); a DialogFragment would mean rebuilding every callback the sheets
 * capture, so the host keeps this instead.
 *
 * Build one per sheet while the Activity is built (it registers under [key]
 * in the saved-instance state). Call [shown] when the sheet opens, with what
 * showing it again needs, and [closed] from its dismiss; or hand an alert to
 * [keep], which does both. After `super.onCreate`, [restored] answers that
 * state once when the screen this one replaces had the sheet open; the host
 * shows it again with it. The old Activity's sheet is dismissed when that
 * Activity is destroyed.
 */
class ReopenAfterRecreate(private val activity: ComponentActivity, private val key: String) {

    private var state: (() -> Bundle)? = null
    private var closer: DefaultLifecycleObserver? = null

    /** The dialog [shown] last, while it is up. */
    private var showing: Dialog? = null

    init {
        activity.savedStateRegistry.registerSavedStateProvider(key) {
            state?.invoke()?.apply { putBoolean(OPEN, true) } ?: Bundle()
        }
    }

    /** [dialog] is on screen; [state] is what showing it again needs. */
    fun shown(dialog: Dialog, state: () -> Bundle = { Bundle() }) {
        closed()
        this.state = state
        showing = dialog
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // The state is saved by now: the rebuilt screen shows its own.
                if (dialog.isShowing) dialog.dismiss()
            }
        }
        closer = observer
        activity.lifecycle.addObserver(observer)
    }

    /** The sheet closed; nothing to show again. */
    fun closed() {
        state = null
        showing = null
        closer?.let(activity.lifecycle::removeObserver)
        closer = null
    }

    /**
     * [dialog], just shown, is [shown] with [state] and [closed] when it is
     * dismissed, unless another dialog has been [shown] here since (a dismiss
     * lands after the click that opened the next one). Returns [dialog].
     */
    fun <D : Dialog> keep(dialog: D, state: () -> Bundle = { Bundle() }): D {
        shown(dialog, state)
        dialog.setOnDismissListener { if (showing === dialog) closed() }
        return dialog
    }

    /** What [shown] saved when the replaced screen had the sheet open, once; null otherwise. */
    fun restored(): Bundle? =
        activity.savedStateRegistry.consumeRestoredStateForKey(key)?.takeIf { it.getBoolean(OPEN) }

    private companion object {
        const val OPEN = "open"
    }
}
