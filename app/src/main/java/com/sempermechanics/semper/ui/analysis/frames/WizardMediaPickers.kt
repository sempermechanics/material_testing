package com.sempermechanics.semper.ui.analysis.frames

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.ui.common.dialog.ReopenAfterRecreate
import com.sempermechanics.semper.ui.common.media.MediaPickerSheet
import com.sempermechanics.semper.ui.common.media.MediaSourceChooser

/**
 * Page 1's pickers: the Photos sheet, with Files (SAF) still reaching
 * DNG/RAW and Drive, which MediaStore may not index. A picked reference goes
 * to [onReference], picked frames to [onDeformed].
 *
 * Construct it in `onCreate`, before the ROI studio's launcher: it registers
 * three result launchers, and their order is how a result finds its launcher
 * after a process death.
 */
class WizardMediaPickers(
    private val activity: AppCompatActivity,
    private val onReference: (Uri) -> Unit,
    private val onDeformed: (List<Uri>) -> Unit,
) {
    private var mediaPicker: MediaPickerSheet? = null

    /** Each sheet, with its picks, comes back after a rotation or theme switch (readiness AE1-111). */
    private val reopenReference = ReopenAfterRecreate(activity, "wizard.referenceSheet")
    private val reopenDeformed = ReopenAfterRecreate(activity, "wizard.deformedSheet")

    private val requestMediaPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPicker?.onPermissionResult()
        }
    private val pickRefFiles =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onReference)
        }
    private val pickDefFiles =
        activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            onDeformed(uris)
        }

    init {
        // Built in onCreate, after super.onCreate: the saved state is readable.
        val reference = reopenReference.restored()
        val deformed = reopenDeformed.restored()
        if (reference != null || deformed != null) {
            activity.window.decorView.post {
                if (reference != null) pickReference()
                if (deformed != null) pickDeformed(MediaPickerSheet.picksIn(deformed))
            }
        }
    }

    fun pickReference() {
        mediaPicker = MediaSourceChooser.show(
            activity = activity,
            mode = MediaSourceChooser.Mode.REFERENCE,
            requestPermission = ::requestPermission,
            onBrowseSaf = { pickRefFiles.launch(arrayOf(IMAGES)) },
            onPicked = { uris -> uris.firstOrNull()?.let(onReference) },
            kept = MediaPickerSheet.Kept(reopenReference),
        )
    }

    /** The frames sheet; [picks] are ticked already (a sheet shown again after a rotation). */
    fun pickDeformed(picks: List<Uri> = emptyList()) {
        mediaPicker = MediaSourceChooser.show(
            activity = activity,
            mode = MediaSourceChooser.Mode.DEFORMED,
            requestPermission = ::requestPermission,
            onBrowseSaf = { pickDefFiles.launch(arrayOf(IMAGES)) },
            onPicked = onDeformed,
            kept = MediaPickerSheet.Kept(reopenDeformed, picks),
        )
    }

    private fun requestPermission() {
        requestMediaPermission.launch(MediaSourceChooser.requiredPermissions(includeVideo = false))
    }

    private companion object {
        const val IMAGES = "image/*"
    }
}
