package com.sempermechanics.semper.ui.settings

import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.policy.FeatureAccess
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.StorageBudget
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding
import com.sempermechanics.semper.ui.common.ByteSize
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.LicenseLock
import com.sempermechanics.semper.ui.common.dialog.bindInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-device storage: sizes, auto-free budget, free-up, and cache clear.
 * Session restore / download / delete stay on [SettingsActivity].
 *
 * [freeUp] is how Free-up and Auto-free show (`FeatureLocks.settings`).
 */
class SettingsStorageSection(
    private val activity: SettingsActivity,
    private val views: ViewSettingsScrollContentBinding,
    private val freeUp: FeatureAccess,
) {
    fun wire() {
        views.btnStorageClearCache.setOnClickListener { clearTemporaryFiles() }
        views.btnAutoFreeInfo.bindInfo(activity, R.string.storage_auto_free, R.string.storage_auto_free_info)
        // Free-up and the auto-free budget evict local copies the cloud can
        // give back — the licensed half of cloud. Without restore neither
        // control works (StorageBudget itself also refuses, so a stale budget
        // pref cannot drop anything): locked when the licence lacks backup,
        // hidden when policy keeps backup off.
        if (freeUp != FeatureAccess.OPEN) {
            if (freeUp == FeatureAccess.LOCKED) lockFreeUp() else hideFreeUp()
            refreshStorageTotals()
            return
        }
        views.btnStorageFreeUp.setOnClickListener { confirmFreeUpSpace() }

        val valueLabel = views.tvAutoFreeValue
        views.sliderAutoFree.apply {
            valueTo = AppSettings.MAX_AUTO_FREE_GB.toFloat()
            value = AppSettings.autoFreeBudgetGb(activity)
                .toFloat().coerceIn(valueFrom, valueTo)
            valueLabel.text = autoFreeText(value.toInt())
            addOnChangeListener { _, v, fromUser ->
                valueLabel.text = autoFreeText(v.toInt())
                if (!fromUser) return@addOnChangeListener
                AppSettings.setAutoFreeBudgetGb(activity, v.toInt())
                // Applying on release rather than on every tick: dragging past a
                // low value would otherwise start dropping sessions mid-gesture.
            }
            addOnSliderTouchListener(
                object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) = applyStorageBudget()
                },
            )
        }

        refreshStorageTotals()
    }

    /** Locked, not hidden: both shown disabled, at Off, under the licence note; the ⓘ still explains. */
    private fun lockFreeUp() {
        views.btnStorageFreeUp.isVisible = true
        views.btnStorageFreeUp.setText(R.string.storage_free_up_confirm)
        views.tvAutoFreeValue.text = autoFreeText(AppSettings.AUTO_FREE_OFF)
        LicenseLock.lock(
            views.groupStorageFreeUp,
            views.tvStorageLockNote,
            listOf(views.btnStorageFreeUp, views.sliderAutoFree),
        )
    }

    private fun hideFreeUp() {
        listOf(
            views.btnStorageFreeUp,
            views.rowAutoFreeHeader,
            views.tvAutoFreeValue,
            views.sliderAutoFree,
        ).forEach { it.isVisible = false }
    }

    /** The slider's short value, "Off" or "Over 8 GB"; the ⓘ explains what it does. */
    private fun autoFreeText(gb: Int): String =
        if (gb <= AppSettings.AUTO_FREE_OFF) {
            activity.getString(R.string.storage_auto_free_off)
        } else {
            activity.getString(R.string.storage_auto_free_on_fmt, gb)
        }

    /** Measures off the main thread — a full sessions tree is a lot of stat calls. */
    private fun refreshStorageTotals() {
        activity.lifecycleScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                Triple(
                    SessionStore.totalSize(activity),
                    // Show what Clear will free — not raw cacheDir size (which
                    // includes a live import the button must not delete).
                    CacheJanitor.clearableUserBytes(activity),
                    StorageBudget.reclaimableBytes(activity),
                )
            }
            val (analyses, cache, reclaimable) = sizes
            views.tvStorageAnalysesSize.text = ByteSize.format(analyses)
            views.tvStorageCacheSize.text = ByteSize.format(cache)
            // Clear only while there is something to clear: hidden and disabled together.
            views.btnStorageClearCache.isVisible = cache > 0
            views.btnStorageClearCache.isEnabled = cache > 0

            // "Free up N MB" only while a backed-up analysis holds local data,
            // and never without cloud restore (wire() locks or hides it then).
            if (freeUp == FeatureAccess.OPEN) {
                views.btnStorageFreeUp.isVisible = reclaimable > 0
                views.btnStorageFreeUp.text =
                    activity.getString(R.string.storage_free_up_fmt, ByteSize.format(reclaimable))
            }
        }
    }

    private fun confirmFreeUpSpace() {
        activity.lifecycleScope.launch {
            val reclaimable = withContext(Dispatchers.IO) {
                StorageBudget.reclaimableBytes(activity)
            }
            if (reclaimable <= 0) {
                activity.toast(activity.getString(R.string.storage_freed_none))
                return@launch
            }
            Dialogs.confirm(
                activity,
                activity.getText(R.string.storage_free_up_title),
                activity.getString(R.string.storage_free_up_body_fmt, ByteSize.format(reclaimable)),
                R.string.storage_free_up_confirm,
            ) { freeUpSpace() }
        }
    }

    private fun freeUpSpace() {
        activity.lifecycleScope.launch {
            val outcome = StorageBudget.freeAllBackedUpOnIo(activity)
            if (outcome.didAnything) {
                activity.toast(
                    activity.resources.getQuantityString(
                        R.plurals.storage_freed_fmt,
                        outcome.sessionsDropped,
                        ByteSize.format(outcome.freedBytes),
                        outcome.sessionsDropped,
                    ),
                )
            } else {
                activity.toast(activity.getString(R.string.storage_freed_none))
            }
            refreshStorageTotals()
            activity.wireAnalysesDataSection()
        }
    }

    private fun clearTemporaryFiles() {
        activity.lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) {
                CacheJanitor.sweepUserRequested(activity)
            }
            if (freed > 0) {
                activity.toast(activity.getString(R.string.storage_cache_cleared_fmt, ByteSize.format(freed)))
            } else {
                activity.toast(activity.getString(R.string.storage_cache_cleared_none))
            }
            refreshStorageTotals()
        }
    }

    private fun applyStorageBudget() {
        activity.lifecycleScope.launch {
            val outcome = StorageBudget.enforceOnIo(activity)
            if (outcome.didAnything) {
                activity.toast(
                    activity.resources.getQuantityString(
                        R.plurals.storage_freed_fmt,
                        outcome.sessionsDropped,
                        ByteSize.format(outcome.freedBytes),
                        outcome.sessionsDropped,
                    ),
                )
                activity.wireAnalysesDataSection()
            }
            refreshStorageTotals()
        }
    }
}
