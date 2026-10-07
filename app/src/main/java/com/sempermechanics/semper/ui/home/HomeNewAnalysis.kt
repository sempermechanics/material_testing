// How Home starts a new analysis: the + button's checks and test-type sheet,
// the source chooser, and the hand-off of the picked media to the wizard.
// Split out of HomeActivity.
package com.sempermechanics.semper.ui.home

import android.content.Intent
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.common.TestTypeSheet
import com.sempermechanics.semper.ui.common.media.MediaSourceChooser
import com.sempermechanics.semper.ui.settings.SettingsActivity

/** The + button, the gear, and the empty state's button (which is the + button). */
internal fun HomeActivity.wireButtons() {
    val fab = binding.fabNewAnalysis
    HomeFabLayout.pinAtNineTenths(binding.homeRoot, fab)
    fab.setOnClickListener {
        // Two independent reasons new work cannot start. The seat check is
        // first because an institution member is licensed, so the quota
        // check below is false for them by definition and would wave them
        // through. btnEmptyRestore delegates here via performClick(), so
        // both entry points are covered by this one listener.
        if (LicenseEntitlements.isSeatRequiredToStart(this)) {
            AnalysisNavHelper.openSeatRequired(this)
            return@setOnClickListener
        }
        // At the account's analysis limit, block new work behind the persistent
        // limit screen (email support) instead of letting it fail on upload.
        if (quotaCard.openLimitScreenIfReached()) return@setOnClickListener
        // Which test first: the wizard decides on open whether to ask for
        // machine loads, so it has to know before any media is picked.
        TestTypeSheet.show(this) { type ->
            pendingTestType = type
            showSourceChooser()
        }
    }
    binding.btnHomeSettings.setOnClickListener {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
    binding.btnEmptyRestore.setOnClickListener {
        fab.performClick()
    }
}

/**
 * In-sheet Images gallery; Files opens SAF for Drive / storage / DNG.
 */
private fun HomeActivity.showSourceChooser() {
    mediaPicker = MediaSourceChooser.show(
        activity = this,
        mode = MediaSourceChooser.Mode.HOME_REFERENCE,
        requestPermission = {
            requestMediaPermission.launch(
                MediaSourceChooser.requiredPermissions(includeVideo = true),
            )
        },
        onBrowseSaf = { pickDocument.launch(arrayOf("image/*", "video/*")) },
        onPicked = { uris -> routePickedMedia(uris.firstOrNull()) },
    )
}

/**
 * Route a picked photo/video into the analysis screen. Shared by both source
 * pickers so the two entry points behave identically; the mime type decides
 * whether we hand off a single reference image or a video to sample frames
 * from.
 */
internal fun HomeActivity.routePickedMedia(uri: android.net.Uri?) {
    if (uri == null) return
    val mime = contentResolver.getType(uri) ?: ""
    val intent = Intent(this, StaticAnalysisActivity::class.java)
    pendingTestType?.let { intent.putExtra(IntentKeys.TEST_TYPE, it.wireName) }
    if (mime.startsWith("video/")) {
        intent.putExtra(IntentKeys.PICKED_VIDEO_URI, uri.toString())
    } else {
        intent.putExtra(IntentKeys.PICKED_REF_URI, uri.toString())
    }
    startActivity(intent)
}
