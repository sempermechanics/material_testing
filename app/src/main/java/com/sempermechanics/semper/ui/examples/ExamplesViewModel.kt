package com.sempermechanics.semper.ui.examples

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.data.examples.ExampleCatalog
import com.sempermechanics.semper.data.examples.ExampleDownloads
import com.sempermechanics.semper.data.examples.ExampleManifest
import com.sempermechanics.semper.data.examples.MediaStoreSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The example sets on [ExamplesActivity], and Save to phone (ADR-031). The
 * save runs on [viewModelScope], so a rotation does not cut it short
 * (ADR-016); leaving the screen cancels it, and its pending files go too.
 */
@MainThread
class ExamplesViewModel(app: Application) : AndroidViewModel(app) {

    private val _sets = MutableStateFlow<List<ExampleManifest>>(emptyList())

    /** The bundled sets, in order; empty until read. */
    val sets: StateFlow<List<ExampleManifest>> = _sets.asStateFlow()

    /** A save under way: files done of the set's total. */
    data class Progress(val title: String, val done: Int, val total: Int)

    private val _saving = MutableStateFlow<Progress?>(null)

    /** The save under way, or null. */
    val saving: StateFlow<Progress?> = _saving.asStateFlow()

    private val outcomeChannel = Channel<ExampleDownloads.Outcome>(Channel.UNLIMITED)

    /** Each save's outcome once, for whichever screen is current. */
    val outcomes: Flow<ExampleDownloads.Outcome> = outcomeChannel.receiveAsFlow()

    /** Where Save to phone writes; tests stand in for MediaStore. */
    @VisibleForTesting
    internal var downloads: () -> ExampleDownloads = {
        ExampleDownloads(app.assets, MediaStoreSink(app.contentResolver))
    }

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            _sets.value = withContext(Dispatchers.IO) { ExampleCatalog.load(app.assets) }
        }
    }

    /** Copies [set] to Downloads; one save at a time. */
    fun save(set: ExampleManifest) {
        if (saveJob?.isActive == true) return
        _saving.value = Progress(set.title, 0, set.files.size)
        saveJob = viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    downloads().save(set) { done, total -> _saving.value = Progress(set.title, done, total) }
                }
            } finally {
                _saving.value = null
            }
            outcomeChannel.send(outcome)
        }
    }

    /** Stops the save under way; what it had written stays, what it was writing goes. */
    fun cancelSave() {
        saveJob?.cancel()
    }
}
