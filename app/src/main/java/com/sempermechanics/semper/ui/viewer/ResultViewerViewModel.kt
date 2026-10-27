package com.sempermechanics.semper.ui.viewer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import com.sempermechanics.semper.data.integrity.RecordIntegrity
import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.FieldKind
import com.sempermechanics.semper.field.FieldStats
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.BeamDeflection
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.viewer.share.ShareExportJobs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Survives configuration changes for the results browser: frame scrubber index,
 * the field on screen (also across process death), the user's fixed colour scales, running exports and each
 * visited field's stats. Heavy bitmaps stay in the Activity (they are bound to
 * view lifetime).
 */
class ResultViewerViewModel(
    app: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(app) {

    private val mutableState = MutableStateFlow(ViewerState(field = savedField()))

    /**
     * What the viewer shows, as one value: the frame set once listed, the
     * frame on screen and the field. A rebuilt viewer (a rotation) reads it
     * back rather than listing the batch again.
     */
    internal val state: StateFlow<ViewerState> = mutableState.asStateFlow()

    /** The frame on screen, by position in the listed batch. */
    var currentFrameIndex: Int
        get() = mutableState.value.frameIndex
        set(value) {
            mutableState.update { it.copy(frameIndex = value) }
        }

    /**
     * The field on screen. One value (it was a data index and a label, set
     * one after the other), kept in the saved state too so a restored viewer
     * opens on it.
     */
    var field: FieldKind
        get() = mutableState.value.field
        set(value) {
            savedState[KEY_FIELD] = value.name
            mutableState.update { it.copy(field = value) }
        }

    /** The listing in flight; see [listFrames]. */
    private var listing: Job? = null

    /**
     * Lists the batch with [read] on [dispatcher], once per view model: a
     * viewer rebuilt by a rotation finds [ViewerState.frameSet] already there
     * (or the listing still running) and starts none of its own. Then, once
     * the first frame can start, checks the record with [check] on the same
     * dispatcher into [ViewerState.integrity]: it hashes every frame, so it
     * never holds the first one up (ADR-028). [read] and [check] must hold
     * plain data only, never the viewer that asked.
     */
    internal fun listFrames(
        dispatcher: CoroutineDispatcher,
        check: () -> RecordIntegrity = { RecordIntegrity.NotRecorded },
        read: () -> ViewerFrameLoader.FrameSet,
    ) {
        if (mutableState.value.frameSet != null || listing?.isActive == true) return
        listing = viewModelScope.launch {
            val set = withContext(dispatcher) { read() }
            useFrameListing(set.batchFiles)
            mutableState.update { it.copy(frameSet = set) }
            val integrity = withContext(dispatcher) { check() }
            mutableState.update { it.copy(integrity = integrity) }
        }
    }

    private fun savedField(): FieldKind =
        savedState.get<String>(KEY_FIELD)?.let { name -> FieldKind.entries.firstOrNull { it.name == name } }
            ?: FieldKind.U

    /** Built on first Results open when the session has loads; a full batch decode, so kept. */
    var stressStrain: StressStrain.Curve? = null

    /** Results plot zoomed to the elastic region E is fitted to, on both surfaces; else the whole test. */
    var showElasticRegion: Boolean = false

    /** Tensile scale and bias typed in Results this visit; null reads the session's. */
    var curveCorrection: CurveCorrection? = null

    /** Bending's deflection correction set in Results; null keeps the one the viewer opened with. */
    var deflectionCorrection: BeamDeflection.Correction? = null

    /**
     * Runs [write] on session [id] off the main thread, then queues the new
     * metadata for a backed-up session's cloud copy (ADR-013, TD-151). Results'
     * Adjust curve and Adjust deflection save through here: on this scope a
     * rotation does not drop the write (ADR-016), and the write and its queue
     * are one short step that finishes even if the screen closes meanwhile.
     */
    fun saveOnSession(id: String?, write: (Context, String) -> Unit) {
        id ?: return
        val appContext: Context = getApplication()
        viewModelScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                write(appContext, id)
                if (SessionStore.get(appContext, id)?.metadataStale == true) SessionMetadataSync.enqueue(appContext, id)
            }
        }
    }

    /**
     * Fixed colour-scale bounds per field, set in the custom-scale dialog.
     * Concurrent: an export reads them off the main thread (the GIF bounds)
     * while the dialog may change them.
     */
    val customBounds: MutableMap<Int, ValueRange> = ConcurrentHashMap()

    /** Share/export jobs; in [viewModelScope], so a rotation does not cancel them. */
    internal val exports = ShareExportJobs(viewModelScope, app.contentResolver)

    /**
     * A picked save-as document (export kind and destination) whose export has
     * not started yet: the picker's answer can reach a viewer whose frames are
     * not listed yet. Held here, and in the saved state for process death, so
     * whichever viewer is current when the frames are known starts it — once.
     */
    val hasPendingSave: Boolean get() = savedState.contains(KEY_SAVE_KIND)

    fun setPendingSave(kind: String, uri: Uri) {
        savedState[KEY_SAVE_URI] = uri
        savedState[KEY_SAVE_KIND] = kind
    }

    /** The pending save-as, cleared as it is handed out so its export starts once. */
    fun takePendingSave(): Pair<String, Uri>? {
        val kind = savedState.remove<String>(KEY_SAVE_KIND)
        val uri = savedState.remove<Uri>(KEY_SAVE_URI)
        return if (kind != null && uri != null) kind to uri else null
    }

    // ── Field metrics cache (caption + peek-sheet extrema) ───────────────────

    /** Immutable per-(frame,field) result: its stats and the indices of its extrema. */
    internal class FieldMetrics(val stats: FieldStats?, val maxIdx: Int, val minIdx: Int)

    /**
     * Memoised [FieldMetrics] keyed by (frameIndex, dataIndex). A decoded frame is
     * immutable, so these never need invalidation — only an LRU size bound. Computing
     * them is one walk of accepted points; caching means a field toggle or a
     * revisited frame costs nothing, and the viewer warms the entry on its
     * background thread so a scrub settle never does the work on the main thread.
     * Guarded by its own monitor (read on Main, written on Dispatchers.Default).
     */
    private val fieldMetricsCache = LinkedHashMap<Long, FieldMetrics>()

    /** The frame listing [fieldMetricsCache]'s keys index into; see [useFrameListing]. */
    private var metricsListing: List<File>? = null

    /**
     * Tells the cache which `.dat` files its frame indices name. A viewer
     * rebuilt over the same listing (a rotation) keeps the cached metrics; a
     * different listing drops them, since index N is then another frame.
     */
    internal fun useFrameListing(files: List<File>) {
        synchronized(fieldMetricsCache) {
            if (files != metricsListing) {
                fieldMetricsCache.clear()
                metricsListing = files
            }
        }
    }

    internal fun fieldMetricsFor(frameIndex: Int, dataIndex: Int, data: FloatArray): FieldMetrics {
        val key = (frameIndex.toLong() shl Int.SIZE_BITS) or (dataIndex.toLong() and LOW_32_BITS)
        synchronized(fieldMetricsCache) { fieldMetricsCache[key]?.let { return it } }
        val stats = FieldStats.fromArray(DicResult.fieldStats(data, dataIndex))
        val (maxIdx, minIdx) = trueExtremaIndices(data, dataIndex)
        val metrics = FieldMetrics(stats, maxIdx, minIdx)
        synchronized(fieldMetricsCache) {
            fieldMetricsCache[key] = metrics
            if (fieldMetricsCache.size > FIELD_METRICS_CACHE_MAX) {
                val eldest = fieldMetricsCache.keys.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        return metrics
    }

    /**
     * Indices of the accepted points that carry this field's true min and max —
     * the same values [DicResult.fieldStats] reports — so the ⓘ coordinates
     * match the printed numbers (not the colour-bar percentile clamp).
     */
    private fun trueExtremaIndices(data: FloatArray, dataIndex: Int): Pair<Int, Int> {
        var maxIdx = -1
        var minIdx = -1
        var maxV = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val v = data[i + dataIndex]
                if (v > maxV) {
                    maxV = v
                    maxIdx = i
                }
                if (v < minV) {
                    minV = v
                    minIdx = i
                }
            }
            i += DicResult.STRIDE
        }
        return maxIdx to minIdx
    }

    private companion object {
        const val KEY_SAVE_KIND = "pending_save_kind"
        const val KEY_SAVE_URI = "pending_save_uri"
        const val KEY_FIELD = "field"

        /** Field metrics are tiny (5 floats + 2 ints); keep plenty across frames/fields. */
        const val FIELD_METRICS_CACHE_MAX = 64

        /** The low half of a cache key, where the field index goes. */
        const val LOW_32_BITS = 0xFFFF_FFFFL
    }
}

/**
 * The result viewer's state, one value ([ResultViewerViewModel.state]):
 * [frameSet] is null until the batch is listed, [integrity] until its record
 * is checked against its manifest.
 */
internal data class ViewerState(
    val frameSet: ViewerFrameLoader.FrameSet? = null,
    val integrity: RecordIntegrity? = null,
    val frameIndex: Int = 0,
    val field: FieldKind = FieldKind.U,
)
