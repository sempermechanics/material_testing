package com.sempermechanics.semper.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import timber.log.Timber
import java.io.File

/**
 * The "Save to Files" copy, held across a rotation of [SaveExportActivity].
 *
 * The copy used to run in the activity's own scope, so a rotation mid-copy
 * cancelled the wait and the recreated proxy started a second copy into the
 * same document while the first, blocking in IO, was still writing it. Here one
 * copy runs per proxy, and whichever instance is current awaits it.
 *
 * The copy runs on [io]; the activity builds this through [factory].
 */
internal class SaveExportViewModel(
    app: Application,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(app) {

    private var copy: Deferred<Boolean>? = null

    /** The one copy of [file] into [uri]: started on the first call, shared after. */
    fun copyOnce(uri: Uri, file: File): Deferred<Boolean> = copy ?: start(uri, file).also { copy = it }

    private fun start(uri: Uri, file: File): Deferred<Boolean> {
        val resolver = getApplication<Application>().contentResolver
        return viewModelScope.async(io) {
            runCatching {
                // Treat the copy as successful only if bytes actually landed —
                // an opened-but-empty stream shouldn't report "Saved".
                val copied = resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: 0L
                copied > 0L
            }.onFailure { Timber.e(it, "Save to Files failed") }.getOrDefault(false)
        }
    }

    companion object {
        /** Builds the view model with its copy on [io]. */
        fun factory(io: CoroutineDispatcher = Dispatchers.IO): ViewModelProvider.Factory = viewModelFactory {
            initializer { SaveExportViewModel(checkNotNull(this[APPLICATION_KEY]), io) }
        }
    }
}
