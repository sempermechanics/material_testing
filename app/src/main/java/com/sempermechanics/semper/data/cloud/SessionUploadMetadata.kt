package com.sempermechanics.semper.data.cloud

import android.content.Context
import com.sempermechanics.semper.data.session.SessionRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session-level metadata JSON for cloud upload: device, time, engine params,
 * and the frame list (`metadata.json`), written through [SessionMetadataDoc].
 *
 * The text is semantically what the org.json writer before it produced, not
 * byte for byte (`50.0` for `50`, `/` for `\/`): the upload declares the sha of
 * the staged bytes, and the backend re-dumps the file.
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

    /** The `metadata.json` text uploaded for [record], stamped now, with this phone's build, device and user. */
    fun buildMetadataJson(record: SessionRecord, context: Context): String =
        SessionMetadataDoc.forUpload(record, context).encode()

    /** The file's `frames` array for [record]: one object per frame, its label, files and sweep settings. */
    fun framesJson(record: SessionRecord): JSONArray = environmentFree(record).getJSONArray("frames")

    /** The file's `engine` object for [record]: the run's settings, ROI, telemetry and sweep. */
    fun engineJson(record: SessionRecord): JSONObject = environmentFree(record).getJSONObject("engine")

    /** [record]'s file without the phone's own parts, which neither view reads. */
    private fun environmentFree(record: SessionRecord): JSONObject = JSONObject(
        SessionMetadataDoc.fromRecord(
            record = record,
            capturedAtUtc = "",
            app = SessionMetadataDoc.App(),
            device = SessionMetadataDoc.Device(),
            user = SessionMetadataDoc.User(),
        ).encode(),
    )
}
