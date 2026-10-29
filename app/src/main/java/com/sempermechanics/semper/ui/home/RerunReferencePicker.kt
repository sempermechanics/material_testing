package com.sempermechanics.semper.ui.home

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.ui.analysis.frames.DecodedReference
import com.sempermechanics.semper.ui.analysis.frames.decodePickedReference
import com.sempermechanics.semper.ui.analysis.frames.isRawName
import com.sempermechanics.semper.ui.analysis.rerun.PickedReference
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.dialog.DeterminateProgressDialog
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.ReopenAfterRecreate
import com.sempermechanics.semper.ui.common.media.MediaPickerSheet
import com.sempermechanics.semper.ui.common.media.MediaSourceChooser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Re-run of an analysis saved before Re-run kept its reference, or restored
 * from a backup, which does not carry it (owner decision, 2026-10-15): its
 * frames and settings are on this phone, its reference is not. [ask] says
 * so and offers the reference picker; the picked photo is decoded as a run
 * would decode it, and must be the analysis's size (`imgW` × `imgH`). If it
 * is not, the user picks another or cancels. Once it is, it goes to the
 * cache ([CacheJanitor.RERUN_REFERENCE_CACHE]) and [onPicked] starts the
 * wizard with it.
 *
 * Construct it while the Activity is initialised, before `onCreate`: it
 * registers two result launchers. The analysis being re-run survives the
 * Activity being recreated, and so does whatever it was showing (readiness
 * AE1-111): its question, its sheet, the wait on a photo being checked (the
 * photo is checked again) or what the check came to.
 *
 * @param decode a picked image's name and its decode ([decodePickedReference]); a test's to fake
 */
internal class RerunReferencePicker(
    private val activity: AppCompatActivity,
    private val onPicked: (SessionRecord, PickedReference) -> Unit,
    private val decode: suspend (Uri) -> Pair<String, DecodedReference>? = {
        decodePickedReference(activity.contentResolver, it)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The local id of the analysis whose reference is being picked. */
    private var sessionId: String? = null
    private var mediaPicker: MediaPickerSheet? = null
    private val checking = SerialJob()

    /** The photo being checked, while it is; checked again on the screen that replaces this one. */
    private var checkingUri: Uri? = null
    private var wait: DeterminateProgressDialog? = null

    /** The question on screen ([ask], [retry] or [unavailable]) and the sheet, kept across a recreate. */
    private val reopenQuestion = ReopenAfterRecreate(activity, "home.rerunQuestion")
    private val reopenSheet = ReopenAfterRecreate(activity, "home.rerunReferenceSheet")

    private val requestPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPicker?.onPermissionResult()
        }
    private val pickFile =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::check) }

    init {
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> restore()
                    // Saved by now: the screen that replaces this one shows its own.
                    Lifecycle.Event.ON_DESTROY -> wait?.dismiss()
                    else -> Unit
                }
            },
        )
    }

    /** Takes back what the replaced screen saved, and shows again what it was showing. */
    private fun restore() {
        val registry = activity.savedStateRegistry
        val saved = registry.consumeRestoredStateForKey(STATE_KEY)
        sessionId = saved?.getString(STATE_KEY)
        registry.registerSavedStateProvider(STATE_KEY) {
            bundleOf(STATE_KEY to sessionId, CHECKING to checkingUri?.toString())
        }
        val question = reopenQuestion.restored()
        val sheet = reopenSheet.restored()
        question?.let(::askAgain)
        if (sessionId == null) return
        if (sheet != null) choose()
        saved?.getString(CHECKING)?.let { check(Uri.parse(it)) }
    }

    /** Shows again the question [state] says was on screen. */
    private fun askAgain(state: Bundle) {
        when (state.getString(KIND)) {
            ASK -> if (sessionId != null) askForReference()
            RETRY -> if (sessionId != null) retry(state.getString(MESSAGE).orEmpty())
            UNAVAILABLE -> unavailable(state.getInt(BODY))
        }
    }

    /** Asks for [record]'s reference: "Pick its reference photo", then the picker. */
    fun ask(record: SessionRecord) {
        sessionId = record.id
        askForReference()
    }

    private fun askForReference() {
        val dialog = Dialogs.confirm(
            activity,
            R.string.rerun_pick_reference_title,
            R.string.rerun_pick_reference_body,
            R.string.rerun_choose_photo,
        ) { choose() }
        reopenQuestion.keep(dialog) { bundleOf(KIND to ASK) }
    }

    /** "Can't re-run this analysis", saying why ([body]); said again after a recreate. */
    fun unavailable(@StringRes body: Int) {
        val dialog = Dialogs.info(activity, R.string.rerun_unavailable_title, body)
        reopenQuestion.keep(dialog) { bundleOf(KIND to UNAVAILABLE, BODY to body) }
    }

    /** The reference picker: Photos, with Files for DNG/RAW and Drive. */
    private fun choose() {
        mediaPicker = MediaSourceChooser.show(
            activity = activity,
            mode = MediaSourceChooser.Mode.REFERENCE,
            requestPermission = {
                requestPermission.launch(MediaSourceChooser.requiredPermissions(includeVideo = false))
            },
            onBrowseSaf = { pickFile.launch(arrayOf(IMAGES)) },
            onPicked = { uris -> uris.firstOrNull()?.let(::check) },
            kept = MediaPickerSheet.Kept(reopenSheet),
        )
    }

    /** Decodes [uri] and checks it against the analysis; Cancel on the wait stops it. */
    internal fun check(uri: Uri) {
        val id = sessionId ?: return
        val wait = DeterminateProgressDialog(
            activity,
            activity.getText(R.string.rerun_checking_photo),
            onCancel = { checking.cancel() },
        ).apply { show() }
        this.wait = wait
        checkingUri = uri
        checking.launch(activity.lifecycleScope) {
            try {
                val outcome = withContext(io) { examine(id, uri) }
                checkingUri = null
                wait.dismiss()
                when (outcome) {
                    is Outcome.Accepted -> onPicked(outcome.record, outcome.picked)
                    is Outcome.WrongSize -> retry(
                        activity.getString(
                            R.string.rerun_reference_size_mismatch,
                            outcome.width,
                            outcome.height,
                            outcome.record.imgW,
                            outcome.record.imgH,
                        ),
                    )
                    is Outcome.Unreadable -> retry(
                        activity.getString(
                            if (outcome.raw) R.string.failed_decode_raw else R.string.failed_load_reference,
                        ),
                    )
                    Outcome.Gone -> unavailable(R.string.rerun_photos_missing_body)
                    Outcome.NoRoom -> unavailable(R.string.rerun_copy_failed_body)
                }
            } finally {
                // Cancel, or this screen going: the wait is over here either way. A
                // recreate saved [checkingUri] first, so the next screen checks again.
                checkingUri = null
                wait.dismiss()
            }
        }
    }

    /** [message], with Choose another photo and Cancel. */
    private fun retry(message: String) {
        val dialog = Dialogs.confirm(
            activity,
            activity.getText(R.string.rerun_pick_reference_title),
            message,
            R.string.rerun_choose_another_photo,
        ) { choose() }
        reopenQuestion.keep(dialog) { bundleOf(KIND to RETRY, MESSAGE to message) }
    }

    /** What the photo at [uri] is to analysis [id]: its reference, kept in the cache, or why not. */
    @WorkerThread
    private suspend fun examine(id: String, uri: Uri): Outcome {
        val record = SessionStore.get(activity, id) ?: return Outcome.Gone
        val picked = try {
            decode(uri)
        } catch (e: IOException) {
            Timber.w(e, "Could not read the picked reference")
            null
        }
        val decoded = picked?.second
        return when {
            decoded == null -> Outcome.Unreadable(isRawName(record.refName))
            decoded.size.width != record.imgW || decoded.size.height != record.imgH ->
                Outcome.WrongSize(record, decoded.size.width, decoded.size.height)
            else -> keep(record, picked?.first.orEmpty(), decoded)
        }
    }

    /** [decoded] in the cache for the wizard, as the reference of [record] picked as [name]. */
    @WorkerThread
    private fun keep(record: SessionRecord, name: String, decoded: DecodedReference): Outcome = try {
        val file = File(activity.cacheDir, CacheJanitor.RERUN_REFERENCE_CACHE)
        file.writeBytes(decoded.bytes)
        Outcome.Accepted(record, PickedReference(file, name))
    } catch (e: IOException) {
        Timber.w(e, "Could not keep the picked reference for a Re-run")
        Outcome.NoRoom
    }

    private sealed interface Outcome {
        class Accepted(val record: SessionRecord, val picked: PickedReference) : Outcome
        class WrongSize(val record: SessionRecord, val width: Int, val height: Int) : Outcome
        class Unreadable(val raw: Boolean) : Outcome
        data object Gone : Outcome
        data object NoRoom : Outcome
    }

    private companion object {
        const val IMAGES = "image/*"
        const val STATE_KEY = "rerun_reference_session"
        const val CHECKING = "checking"
        const val KIND = "kind"
        const val ASK = "ask"
        const val RETRY = "retry"
        const val UNAVAILABLE = "unavailable"
        const val MESSAGE = "message"
        const val BODY = "body"
    }
}
