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
 * showing it again needs, and [closed] from its dismiss. After
 * `super.onCreate`, [restored] answers that state once when the screen this
 * one replaces had the sheet open; the host shows it again with it. The old
 * Activity's sheet is dismissed when that Activity is destroyed.
 */
class ReopenAfterRecreate(private val activity: ComponentActivity, private val key: String) {

    private var state: (() -> Bundle)? = null
    private var closer: DefaultLifecycleObserver? = null

    init {
        activity.savedStateRegistry.registerSavedStateProvider(key) {
            state?.invoke()?.apply { putBoolean(OPEN, true) } ?: Bundle()
        }
    }

    /** [dialog] is on screen; [state] is what showing it again needs. */
    fun shown(dialog: Dialog, state: () -> Bundle = { Bundle() }) {
        closed()
        this.state = state
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
        closer?.let(activity.lifecycle::removeObserver)
        closer = null
    }

    /** What [shown] saved when the replaced screen had the sheet open, once; null otherwise. */
    fun restored(): Bundle? =
        activity.savedStateRegistry.consumeRestoredStateForKey(key)?.takeIf { it.getBoolean(OPEN) }

    private companion object {
        const val OPEN = "open"
    }
}
