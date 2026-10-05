package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.data.CurveCorrection
import com.sempermechanics.semper.data.SpecimenGeometry
import com.sempermechanics.semper.data.TestType
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File

/**
 * One completed (or re-run) analysis as shown on the Home list. Metadata only —
 * the heavy artifacts (.dat frames, reference copy) live in [SessionStore.dirFor],
 * and full result files live in the cloud once synced.
 */
@Serializable
data class SessionRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val frameCount: Int,
    // Engine parameters of the latest run
    val subset: Int,
    val step: Int,
    val strainWindow: Int,
    val use6x6: Boolean = false,
    // Geometry
    val imgW: Int,
    val imgH: Int,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    // Files
    val refPath: String,
    val refName: String,
    val sessionDir: String,
    val defNames: List<String> = emptyList(),
    // Headline shown on the row, e.g. "97.5% converged"
    val headline: String = "",
    val engineStats: List<Float> = emptyList(),
    // Run metrics — kept here (not just in the worker's input Data) so a
    // re-upload triggered by cloud reconciliation is still complete.
    val strainMethod: String = "",
    val pointsConverged: Int = 0,
    val avgIterations: Float = 0f,
    val executionTimeMs: Int = 0,
    /** Backend session id of the cloud copy — needed to erase it. Blank if never synced. */
    val cloudSessionId: String = "",
    val syncState: SyncState = SyncState.LOCAL_ONLY,

    /**
     * The cloud copy's metadata.json predates a change made here after the
     * backup (a rename, bending's deflection correction or the tensile
     * curve's), so [SessionMetadataSync] still has to send it. Cleared once
     * the backend holds the current metadata.
     */
    val metadataStale: Boolean = false,

    // ── Parameter sweep (VsgStudy)
    // A sweep varies the settings instead of the image, so [subset], [step] and
    // [strainWindow] above only describe its first frame. These carry the rest,
    // and their emptiness is what marks an ordinary analysis.

    /** Per-frame subset sizes; empty unless this session is a sweep. */
    val sweepSubsets: List<Int> = emptyList(),

    /** Per-frame step sizes. Rendering a frame depends on its own pitch. */
    val sweepSteps: List<Int> = emptyList(),

    /** Per-frame strain windows. */
    val sweepStrainWindows: List<Int> = emptyList(),

    /** Labels naming each combination, shown in the viewer and the report. */
    val sweepLabels: List<String> = emptyList(),

    /** True when the sweep's line cut runs along x. */
    val lineCutHorizontal: Boolean = true,

    // Combinations the engine could not solve — kept so the lattice still
    // shows hollow nodes after a Home reopen (and after a cloud restore).
    val sweepSkipSubsets: List<Int> = emptyList(),
    val sweepSkipSteps: List<Int> = emptyList(),
    val sweepSkipStrainWindows: List<Int> = emptyList(),

    /**
     * Engine code per skipped combination, index-aligned with the lists above.
     * Stored rather than resolved so the lattice can still say *why* each node
     * is hollow after a reopen — without it the reasons only survive until the
     * screen is left.
     */
    val sweepSkipCodes: List<Int> = emptyList(),

    /** Typed skips; legacy parallel lists remain for old on-disk JSON. */
    val sweepSkippedNodes: List<SkippedNode> = emptyList(),

    /**
     * Why a run ended before it finished, as an engine/run code, or 0 when it
     * ran to completion. Kept with the analysis because a short run otherwise
     * looks exactly like a shorter test that ran cleanly.
     */
    val stopCode: Int = 0,

    /** Frames the run set out to solve; 0 for records predating this field. */
    val plannedFrameCount: Int = 0,

    /**
     * True once the user has renamed this session, so a re-run keeps their name
     * instead of regenerating the auto-name. Auto-names ARE regenerated per run
     * so a sweep re-run as a single (or vice-versa) stops carrying the old kind.
     */
    val renamedByUser: Boolean = false,

    // ── Mechanical test
    // Which test the frames were photographed under, the specimen dimensions
    // its stress needs, and the machine's load per frame. Blank / empty on every
    // record written before test types existed, and readers treat blank as
    // "no test type" rather than defaulting to one.

    /** [TestType.wireName], or "" when none was chosen. */
    val testType: String = "",

    /** Specimen cross-section in mm²; 0 when not entered. */
    val crossSectionMm2: Float = 0f,

    /** Strain axis for the stress–strain curve: Exx when true, Eyy when false. */
    val loadAxisX: Boolean = true,

    /** Bending dimensions; [SpecimenGeometry.NONE] on every other session. */
    val geometry: SpecimenGeometry = SpecimenGeometry.NONE,

    /** Tensile scale and bias typed in Results; [CurveCorrection.NONE] until then. */
    val curveCorrection: CurveCorrection = CurveCorrection.NONE,

    /**
     * Signed load in newtons per deformed frame, index-aligned with [defNames].
     * NaN (written as `null`) is a frame the time match found no log row for.
     */
    @Serializable(with = LoadsSerializer::class)
    val loadsN: List<Float> = emptyList(),

    /** Display name of the CSV the loads came from. */
    val loadSource: String = "",

    /** How CSV rows were matched to frames (a `MachineLoadMapping` name). */
    val loadMapping: String = "",

) {

    /**
     * True when this analysis has a cloud copy, or one on its way: a change to
     * what its metadata.json carries then has to reach it ([metadataStale]).
     */
    val hasCloudCopy: Boolean
        get() = syncState != SyncState.LOCAL_ONLY || cloudSessionId.isNotBlank()

    /**
     * True when every frame has a load. A count that disagrees with [defNames]
     * is treated as no loads at all: half a curve is worse than none.
     */
    val hasMachineLoads: Boolean
        get() = loadsN.isNotEmpty() && loadsN.size == defNames.size

    /** True when the run stopped itself before working through every frame. */
    val stoppedEarly: Boolean get() = runStop.stoppedEarly

    /** True when the frames are parameter combinations rather than images. */
    val isSweep: Boolean get() = sweepSteps.isNotEmpty()

    /**
     * What each frame is called in the viewer and its reports: a sweep's
     * combination labels, else the deformed images' own names.
     */
    val frameNames: List<String> get() = if (isSweep) sweepLabels else defNames

    /** Planned combinations that never produced a frame. */
    val sweepSkipCount: Int
        get() {
            if (sweepSkippedNodes.isNotEmpty()) return sweepSkippedNodes.size
            return minOf(
                sweepSkipSubsets.size,
                sweepSkipSteps.size,
                sweepSkipStrainWindows.size,
            )
        }

    /** Typed [sweepSkippedNodes] first; else legacy parallel lists on disk. */
    fun resolvedSkipNodes(): List<SkippedNode> {
        if (sweepSkippedNodes.isNotEmpty()) return sweepSkippedNodes
        return SkippedNode.fromLegacyArrays(
            sweepSkipSubsets,
            sweepSkipSteps,
            sweepSkipStrainWindows,
            sweepSkipCodes,
        )
    }

    @Serializable
    enum class SyncState {
        LOCAL_ONLY,
        PENDING,
        SYNCED,

        /**
         * Backup was refused for a reason retrying can't fix — the cloud
         * analysis quota is full, the session is too large, or the device
         * isn't authorised. Surfaced on the Home row so it isn't silent.
         */
        FAILED,
    }

    /** True when the frame data is still on this phone (Results can reopen). */
    fun hasLocalData(): Boolean {
        val dir = File(sessionDir)
        return dir.isDirectory && (dir.listFiles { f -> f.extension == "dat" }?.isNotEmpty() == true)
    }
}

/**
 * [SessionRecord.loadsN] with NaN stored as JSON `null`: standard JSON has no
 * NaN, and a frame without a load must keep its place in the list.
 */
internal object LoadsSerializer : KSerializer<List<Float>> {
    private val delegate = ListSerializer(Float.serializer().nullable)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<Float>) =
        encoder.encodeSerializableValue(delegate, value.map { it.takeIf(Float::isFinite) })

    override fun deserialize(decoder: Decoder): List<Float> =
        decoder.decodeSerializableValue(delegate).map { it ?: Float.NaN }
}
