package com.indicvision.semper.data

import android.content.Context
import android.os.Build
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.data.net.TokenStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Session-level metadata JSON for cloud upload: device, time, engine params,
 * and the frame list (`metadata.json`).
 */
object SessionUploadMetadata {

    /**
     * Backup layout version, and the one field a restore is allowed to branch on.
     *
     * - `/2` and earlier: a single `Session.zip` holding raw/, dat/, csv/, reports/
     *   and processed/ together.
     * - `/3`: the payload is split — `Session.zip` carries only raw/ + dat/, and the
     *   derived deliverables live in a separate `Extras.zip` that a restore skips.
     *
     * Restore reads this to know whether the bundle it is about to fetch is the small
     * split one or a legacy everything-archive (see `CloudRestore.isSplitLayout`).
     * Bump it only when that distinction changes, and keep the parse tolerant:
     * pre-`/3` backups predate the field being read at all.
     */
    const val SCHEMA = "indic.session.metadata/6"

    /** Layout version at which the restore payload was split out of the bundle. */
    const val SCHEMA_SPLIT_BUNDLE = 3

    /**
     * Version that added the `test` object and per-frame `loadN`. Purely
     * additive: a `/3` reader ignores them and a `/4` reader of a `/3` file
     * gets a session with no test type.
     */
    const val SCHEMA_MECHANICAL_TEST = 4

    /**
     * Version that added `test.geometry` (the bending span / width /
     * thickness). Additive like `/4`: absent on a tensile test and on every
     * earlier file, and read back as "not entered".
     */
    const val SCHEMA_SPECIMEN_GEOMETRY = 5

    /**
     * Version that added `test.geometry.loadPoint` (the bending edge taps, in
     * reference pixels). Additive: written only when both edges are tapped,
     * and a `/5` file restores with no taps.
     */
    const val SCHEMA_LOAD_POINT = 6

    /** One JSON object per frame: its label, files, and (for a sweep) its settings. */
    fun framesJson(record: SessionRecord): JSONArray {
        val frames = JSONArray()
        record.defNames.forEachIndexed { index, name ->
            val frameObj = JSONObject()
                .put("index", index)
                .put(
                    "frame",
                    if (record.isSweep) {
                        record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
                    } else {
                        "Frame_${index + 1}"
                    },
                )
                .put("image", name)
                .put("dat", SessionPaths.frameDatName(index))
            // A frame the time match found no row for has no `loadN` at all.
            if (record.hasMachineLoads) {
                record.loadsN.loadOfFrame(index)?.let { frameObj.put("loadN", it.toDouble()) }
            }
            if (record.isSweep) {
                val subset = record.sweepSubsets.getOrElse(index) { record.subset }
                val step = record.sweepSteps.getOrElse(index) { record.step }
                val window = record.sweepStrainWindows.getOrElse(index) { record.strainWindow }
                frameObj
                    .put("subset", subset)
                    .put("step", step)
                    // The stored window is already the VSG in px (the engine's diameter).
                    .put("strainWindow", window)
                    .put("vsg", window)
            }
            frames.put(frameObj)
        }
        return frames
    }

    fun buildMetadataJson(record: SessionRecord, context: Context): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val frames = framesJson(record)
        val metrics = JSONObject()
            .put("pointsConverged", record.pointsConverged)
            .put("avgIterations", record.avgIterations.toDouble())
            .put("executionTimeMs", record.executionTimeMs)
        if (record.isSweep) {
            metrics
                .put("isSweep", true)
                .put("sweepSolved", record.frameCount)
                .put("sweepSkipped", record.sweepSkipCount)
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("localSessionId", record.id)
            .put("name", record.name)
            .put("specimen", record.refName)
            .put("capturedAtUtc", iso)
            .put("frameCount", record.frameCount)
            .put("analysisKind", if (record.isSweep) "vsg_study" else "batch")
            .putOpt("test", testJson(record))
            // One combined CSV for the whole analysis (every frame's points, keyed
            // by the leading columns) rather than a file per frame.
            .put("csv", "analysis_data.csv")
            .put("frames", frames)
            .put(
                "app",
                JSONObject()
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE),
            )
            .put("device", deviceJson(context))
            .put(
                "user",
                JSONObject()
                    .put("uid", TokenStore.cachedUid(context))
                    .put("email", TokenStore.cachedEmail(context)),
            )
            .put("engine", engineJson(record))
            .put("metrics", metrics)
            .toString(2)
    }

    /**
     * The mechanical test behind the session, or null when none was chosen so
     * an untyped session's metadata is byte-identical to what it was before.
     */
    fun testJson(record: SessionRecord): JSONObject? {
        if (record.testType.isBlank()) return null
        return JSONObject()
            .put("type", record.testType)
            .put("crossSectionMm2", record.crossSectionMm2.toDouble())
            .put("loadAxis", if (record.loadAxisX) "x" else "y")
            .put("loadUnit", "N")
            .put("loadSource", record.loadSource)
            .put("loadMapping", record.loadMapping)
            .apply { geometryJson(record.geometry)?.let { put("geometry", it) } }
    }

    /** The entered dimensions only, or null when none — every other test stays byte-identical to `/4`. */
    private fun geometryJson(geometry: SpecimenGeometry): JSONObject? {
        if (geometry.isNone) return null
        val json = JSONObject()
        fun putIf(key: String, value: Float) {
            if (value > 0f) json.put(key, value.toDouble())
        }
        putIf("spanMm", geometry.spanMm)
        putIf("widthMm", geometry.widthMm)
        putIf("thicknessMm", geometry.thicknessMm)
        geometry.loadPoint.takeIf { it.isSet }?.let { taps ->
            json.put(
                "loadPoint",
                JSONObject()
                    .put("topX", taps.topX.toDouble())
                    .put("topY", taps.topY.toDouble())
                    .put("bottomX", taps.bottomX.toDouble())
                    .put("bottomY", taps.bottomY.toDouble()),
            )
        }
        return json
    }

    fun deviceJson(context: Context): JSONObject = JSONObject()
        .put("id", DeviceKeyManager(context).getDeviceId())
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("os", "Android ${Build.VERSION.RELEASE}")
        .put("sdkInt", Build.VERSION.SDK_INT)

    fun engineJson(record: SessionRecord): JSONObject {
        val engine = JSONObject()
            .put("subset", record.subset)
            .put("step", record.step)
            .put("strainWindow", record.strainWindow)
            .put("strainMethod", record.strainMethod)
            .put("use6x6", record.use6x6)
            .put("imageWidth", record.imgW)
            .put("imageHeight", record.imgH)
            .put(
                "roi",
                JSONObject()
                    .put("x", record.roiX).put("y", record.roiY)
                    .put("w", record.roiW).put("h", record.roiH),
            )
            .put("stats", JSONArray(record.engineStats))
        if (record.isSweep) {
            engine.put(
                "sweep",
                JSONObject()
                    .put("lineCutHorizontal", record.lineCutHorizontal)
                    .put("subsets", JSONArray(record.sweepSubsets))
                    .put("steps", JSONArray(record.sweepSteps))
                    .put("strainWindows", JSONArray(record.sweepStrainWindows))
                    .put("labels", JSONArray(record.sweepLabels))
                    .put(
                        "skipped",
                        JSONObject()
                            .put("nodes", SkippedNode.toMetadataJsonArray(record.resolvedSkipNodes())),
                    ),
            )
        }
        return engine
    }
}
