package com.sempermechanics.semper.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.rerun.PickedReference
import com.sempermechanics.semper.ui.analysis.rerun.RerunSource
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.ReopenAfterRecreate
import com.sempermechanics.semper.ui.common.media.MediaPickerSheet
import com.sempermechanics.semper.ui.common.media.MediaSourceChooser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home's two ways into a new analysis: the + button's source chooser (a
 * photo or a video, from the gallery sheet or the Files picker) and an older
 * analysis's Re-run. It registers Activity Result launchers, so Home builds
 * it as a property, before the screen starts.
 *
 * Its gates read the licence, which reaches EntitlementStore (Keystore work on a
 * first read or a clock re-seal), so they run on [io] and the screen acts after
 * (readiness TE1-006): a tap never reads the entitlement on the main thread.
 *
 * @param limitReached opens the limit screen and answers true when the
 *   account is at its analysis limit ([HomeQuotaCard.openLimitScreenIfReached],
 *   which checks off the main thread).
 */
@MainThread
internal class HomeNewAnalysis(
    private val activity: AppCompatActivity,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limitReached: suspend () -> Boolean,
) {

    /** Files tab: Storage Access Framework (Drive, storage, DNG). */
    private val pickDocument =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            routePickedMedia(uri)
        }

    private var mediaPicker: MediaPickerSheet? = null

    /** The source chooser comes back after a rotation or theme switch (readiness AE1-111). */
    private val reopenChooser = ReopenAfterRecreate(activity, "home.sourceChooser")

    /** An older analysis's Re-run: its reference picked again. Registers launchers, so built with the screen. */
    private val rerunReference = RerunReferencePicker(activity, onPicked = ::openRerun)
    private val requestMediaPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPicker?.onPermissionResult()
        }

    /** True while a tap's gates are being checked, so a second tap does not open a second chooser. */
    private var checking = false

    /** The + button: the source chooser, when new work may start (checked off the main thread). */
    fun start() {
        if (checking) return
        checking = true
        activity.lifecycleScope.launch {
            try {
                if (mayStart()) showSourceChooser()
            } finally {
                checking = false
            }
        }
    }

    /**
     * Whether new work may start, having opened the screen that says why not
     * when it may not: the + button and Re-run both ask. Two independent
     * reasons: the seat check is first because an institution member is
     * licensed, so the quota check below is false for them by definition and
     * would wave them through. At the account's analysis limit, new work is
     * blocked behind the persistent limit screen (email support) instead of
     * failing on upload.
     */
    private suspend fun mayStart(): Boolean = when {
        // A build the backend no longer supports: its gate screen is opened.
        !AnalysisNavHelper.ensureUpToDate(activity) -> false
        withContext(io) { LicenseEntitlements.isSeatRequiredToStart(activity) } -> {
            AnalysisNavHelper.openSeatRequired(activity)
            false
        }
        else -> !limitReached()
    }

    /**
     * Re-run: opens the wizard on a new analysis copied from [record]'s
     * photos and settings (owner decision, 2026-10-15: a saved analysis is
     * never re-run in place). One saved before Re-run kept its reference, or
     * restored, asks for its reference again ([RerunReferencePicker]); one
     * whose frames are not here (freed up) says so. The new analysis counts
     * toward the limit like any other, so the gates come first.
     */
    fun rerun(record: SessionRecord) {
        activity.lifecycleScope.launch {
            val source = withContext(Dispatchers.IO) { RerunSource.of(record) }
            when {
                source == RerunSource.MISSING -> Dialogs.info(
                    activity,
                    R.string.rerun_unavailable_title,
                    R.string.rerun_photos_missing_body,
                )
                !mayStart() -> Unit
                source == RerunSource.NEEDS_REFERENCE -> rerunReference.ask(record)
                else -> openRerun(record, null)
            }
        }
    }

    /** The wizard on [record]'s Re-run, with the reference picked again when [picked]. */
    private fun openRerun(record: SessionRecord, picked: PickedReference?) {
        val intent = Intent(activity, StaticAnalysisActivity::class.java)
            .putExtra(IntentKeys.RERUN_SESSION_ID, record.id)
        if (picked != null) {
            intent.putExtra(IntentKeys.RERUN_REFERENCE_PATH, picked.file.absolutePath)
                .putExtra(IntentKeys.RERUN_REFERENCE_NAME, picked.name)
        }
        activity.startActivity(intent)
    }

    /** Shows the source chooser again when the screen this one replaces had it open; call after `super.onCreate`. */
    fun reopenAfterRecreate() {
        if (reopenChooser.restored() != null) showSourceChooser()
    }

    /** In-sheet Images gallery; Files opens SAF for Drive / storage / DNG. */
    private fun showSourceChooser() {
        mediaPicker = MediaSourceChooser.show(
            activity = activity,
            mode = MediaSourceChooser.Mode.HOME_REFERENCE,
            requestPermission = {
                requestMediaPermission.launch(
                    MediaSourceChooser.requiredPermissions(includeVideo = true),
                )
            },
            onBrowseSaf = { pickDocument.launch(arrayOf("image/*", "video/*")) },
            onPicked = { uris -> routePickedMedia(uris.firstOrNull()) },
            kept = MediaPickerSheet.Kept(reopenChooser),
        )
    }

    /**
     * Route a picked photo/video into the analysis screen. Shared by both source
     * pickers so the two entry points behave identically; the mime type decides
     * whether we hand off a single reference image or a video to sample frames
     * from.
     */
    private fun routePickedMedia(uri: Uri?) {
        if (uri == null) return
        val mime = activity.contentResolver.getType(uri) ?: ""
        val intent = Intent(activity, StaticAnalysisActivity::class.java)
        if (mime.startsWith("video/")) {
            intent.putExtra(IntentKeys.PICKED_VIDEO_URI, uri.toString())
        } else {
            intent.putExtra(IntentKeys.PICKED_REF_URI, uri.toString())
        }
        activity.startActivity(intent)
    }
}
