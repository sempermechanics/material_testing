package com.sempermechanics.semper.ui.viewer

import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.entitlement.DeviceKeyThumbprint
import com.sempermechanics.semper.data.integrity.LocalRecordKey
import com.sempermechanics.semper.data.integrity.RecordIntegrity
import com.sempermechanics.semper.data.integrity.RecordManifestWriter
import com.sempermechanics.semper.data.integrity.RecordSigner
import com.sempermechanics.semper.data.session.SessionRecordSettings
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.SoftwareRecordSigner
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.fixtures.viewerArgs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The viewer's provenance sheet: the rows it reads off the viewer's arguments
 * for the frame on screen. It is where "what produced this result, and why is
 * it short?" gets answered months later, so a row that shows the run's first
 * setting instead of the frame's, or hides why a run stopped, misleads.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsUsedSheetTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    /** This phone's device key, as the viewer's check reads it (Robolectric has no Keystore). */
    private val phoneKey = SoftwareRecordSigner()

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
    }

    @Before
    fun writeBatch() {
        LocalRecordKey.useForTest { DeviceKeyThumbprint.of(phoneKey.publicKeyDer()) }
        batchDir = temp.newFolder("batch")
        for (f in 0 until FRAMES) {
            val points = GRID * GRID
            val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
            for (i in 0 until points) {
                buffer.putFloat(((i % GRID) * STEP).toFloat())
                buffer.putFloat(((i / GRID) * STEP).toFloat())
                buffer.putFloat(f.toFloat()).putFloat(0f)
                buffer.putFloat(0.001f * (i % GRID)).putFloat(0.002f).putFloat(0f)
                buffer.putFloat(0.01f)
            }
            File(batchDir, "frame_%03d.dat".format(f)).writeBytes(buffer.array())
        }
    }

    @After
    fun forgetPhoneKey() = LocalRecordKey.useForTest()

    /** A plain three-frame run over the whole 16×16 image, opened on frame 1. */
    private fun baseArgs() = viewerArgs(batchDir, GRID, STEP).copy(strainWindow = 25, refName = "Dogbone A")

    private fun viewer(args: ViewerArgs = baseArgs()): ResultViewerActivity {
        val activity = launchViewer(args)
        idleUntil("the viewer") { activity.rawData != null }
        idleUntil("the record check") { activity.viewModel.state.value.integrity != null }
        return activity
    }

    private fun ResultViewerActivity.rows(): Map<String, String> = SettingsUsedSheet.entriesFor(this).toMap()

    @Test
    fun `a plain run lists its settings, ROI and image size in order`() {
        val host = viewer()
        val entries = SettingsUsedSheet.entriesFor(host)

        assertEquals(
            listOf(
                host.getString(R.string.settings_used_subset) to "41 px",
                host.getString(R.string.settings_used_step) to "4 px",
                // VSG 25 px at step 4 is a 7-point window.
                host.getString(R.string.settings_used_strain_window) to "7-point window · VSG 25 px",
                host.getString(R.string.settings_used_strain_method) to "VSG",
                host.getString(R.string.param_reference) to "First image",
                // Units on the ROI too, as on the image size (readiness AE1-112).
                host.getString(R.string.settings_used_roi) to "16 × 16 px at (0, 0) px",
                host.getString(R.string.settings_used_image_size) to "16 × 16 px",
                // No record_manifest.json beside these frames (ADR-028).
                host.getString(R.string.settings_used_integrity) to "Not recorded",
            ),
            entries,
        )
    }

    @Test
    fun `no ROI row when none was recorded`() {
        val host = viewer(baseArgs().copy(roiW = 0, roiH = 0))
        assertTrue(host.getString(R.string.settings_used_roi) !in host.rows())
    }

    @Test
    fun `a run that stopped early says why and how far it got`() {
        val code = 3
        val host = viewer(baseArgs().copy(stopCode = code, plannedFrames = 5))
        val entries = SettingsUsedSheet.entriesFor(host)
        val labels = entries.map { it.first }
        val rows = entries.toMap()

        assertEquals(
            "Unknown engine error (code 3)",
            rows[host.getString(R.string.settings_used_stopped_early)],
        )
        assertEquals("3 of 5 frames", rows[host.getString(R.string.settings_used_frames_solved)])
        assertEquals(
            "stop rows sit between the settings and the ROI",
            labels.indexOf(host.getString(R.string.param_reference)) + 1,
            labels.indexOf(host.getString(R.string.settings_used_stopped_early)),
        )
    }

    @Test
    fun `an old record with no planned count still says why it stopped`() {
        val host = viewer(baseArgs().copy(stopCode = 3, plannedFrames = 0))
        val rows = host.rows()

        assertTrue(host.getString(R.string.settings_used_stopped_early) in rows)
        assertTrue(host.getString(R.string.settings_used_frames_solved) !in rows)
    }

    @Test
    fun `a finished run has no stop rows`() {
        val rows = viewer().rows()
        assertTrue(rows.keys.none { it == "Stopped early" || it == "Frames solved" })
    }

    @Test
    fun `the reference row says which image each frame was solved against, in the wizard's words`() {
        val label = { host: ResultViewerActivity -> host.getString(R.string.param_reference) }

        val fixed = viewer(baseArgs().copy(refUpdateInterval = 0))
        assertEquals("First image", fixed.rows()[label(fixed)])
        val everyFrame = viewer(baseArgs().copy(refUpdateInterval = 1))
        assertEquals("Every frame", everyFrame.rows()[label(everyFrame)])
        val everyTwo = viewer(baseArgs().copy(refUpdateInterval = 2))
        assertEquals("Every 2 frames", everyTwo.rows()[label(everyTwo)])
        assertEquals(
            "a row's interval reaches the sheet",
            "Every 5 frames",
            referenceText(everyTwo, ViewerArgs.of(sessionRecord().copy(refUpdateInterval = 5)).refUpdateInterval),
        )
    }

    private fun integrityRow(host: ResultViewerActivity) = host.rows()[host.getString(R.string.settings_used_integrity)]

    private fun writeManifest(signer: RecordSigner?) {
        val settings = SessionRecordSettings(41, STEP, 25, 0, 0, GRID * STEP, GRID * STEP, false)
        RecordManifestWriter.write(batchDir, settings, signer)
    }

    @Test
    fun `a record this phone's key signed reads Verified, with no warning`() {
        writeManifest(phoneKey)
        val host = viewer()

        assertEquals("Verified", integrityRow(host))
        SettingsUsedSheet.show(host)
        val sheet = ShadowDialog.getLatestDialog()
        assertEquals(View.GONE, sheet.findViewById<View>(R.id.integrityWarnRow).visibility)
    }

    @Test
    fun `an unsigned manifest reads Not signed`() {
        writeManifest(signer = null)

        assertEquals("Not signed", integrityRow(viewer()))
    }

    @Test
    fun `an edited frame reads Changed since analysis and the warning names it`() {
        writeManifest(phoneKey)
        File(batchDir, "frame_001.dat").appendBytes(ByteArray(DicResult.BYTES_PER_POINT))
        val host = viewer()

        assertEquals("Changed since analysis", integrityRow(host))
        SettingsUsedSheet.show(host)
        val sheet = ShadowDialog.getLatestDialog()
        val warn = sheet.findViewById<View>(R.id.integrityWarnRow)
        assertEquals(View.VISIBLE, warn.visibility)
        val text = warn.findViewById<TextView>(R.id.tvWarnText).text.toString()
        assertTrue(text, text.endsWith(": frame_001.dat"))
    }

    @Test
    fun `a record another device's key signed reads Signed on another device, never Verified`() {
        val other = SoftwareRecordSigner()
        writeManifest(other)
        val host = viewer()

        assertEquals("Signed on another device", integrityRow(host))
        SettingsUsedSheet.show(host)
        val warn = ShadowDialog.getLatestDialog().findViewById<View>(R.id.integrityWarnRow)
        assertEquals(View.VISIBLE, warn.visibility)
        val text = warn.findViewById<TextView>(R.id.tvWarnText).text.toString()
        val short = DeviceKeyThumbprint.of(other.publicKeyDer()).take(8) + "…"
        assertTrue(text, text.contains("(key $short)") && text.contains("registered phones"))
    }

    @Test
    fun `the warning names three changed files and counts the rest`() {
        val host = viewer()
        val changed = RecordIntegrity.Changed(listOf("a.dat", "b.dat", "c.dat", "d.dat", "e.dat"), "0123456789abcdef")

        val text = checkNotNull(integrityWarning(host, changed))

        assertTrue(text, text.endsWith(": a.dat, b.dat, c.dat and 2 more files"))
        assertEquals(null, integrityWarning(host, RecordIntegrity.Unsigned("0123456789abcdef")))
        assertEquals("Checking…", integrityText(host, null))
    }

    private fun sweepArgs(startFrame: Int) = baseArgs().copy(
        startFrame = startFrame,
        sweep = ViewerSweepArgs(
            subsets = listOf(21, 31, 41),
            steps = listOf(STEP, STEP, STEP),
            strainWindows = listOf(17, 33, 41),
            lineCutHorizontal = true,
            skippedJson = "[]",
        ),
    )

    @Test
    fun `a sweep describes the combination on screen, not the first one`() {
        val host = viewer(sweepArgs(startFrame = 1))
        val rows = host.rows()

        assertEquals("31 px", rows[host.getString(R.string.settings_used_subset)])
        assertEquals("9-point window · VSG 33 px", rows[host.getString(R.string.settings_used_strain_window)])
    }

    @Test
    fun `the sheet shows the specimen and one row per entry, divided`() {
        val host = viewer()
        SettingsUsedSheet.show(host)
        val sheet = ShadowDialog.getLatestDialog()
        val rows = sheet.findViewById<LinearLayout>(R.id.settingsUsedRows)
        val entries = SettingsUsedSheet.entriesFor(host)

        assertTrue(sheet.isShowing)
        assertEquals("Dogbone A", sheet.findViewById<TextView>(R.id.tvSettingsUsedSpecimen).text.toString())
        assertEquals(entries.size * 2 - 1, rows.childCount)
        val first = rows.getChildAt(0) as LinearLayout
        assertEquals(entries[0].first, (first.getChildAt(0) as TextView).text.toString())
        assertEquals(entries[0].second, (first.getChildAt(1) as TextView).text.toString())
        assertEquals(
            "a still frame shows its histogram",
            View.VISIBLE,
            sheet.findViewById<View>(R.id.distributionSection).visibility,
        )
    }

    @Test
    fun `a sweep's sheet adds the line cut through the ROI centre`() {
        val host = viewer(sweepArgs(startFrame = 0))
        SettingsUsedSheet.show(host)
        val sheet = ShadowDialog.getLatestDialog()

        assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.lineCutSection).visibility)
        val legend = sheet.findViewById<TextView>(R.id.tvLineCutLegend).text.toString()
        val axis = host.getString(R.string.axis_x)
        assertTrue(legend, legend.startsWith("Cut along $axis through the ROI centre at 8 px."))
    }

    @Test
    fun `the line cut legend gives each strain component its own swatch`() {
        val host = viewer()
        val legend = SettingsUsedSheet.lineCutLegend(host, "y", 12f)
        val text = legend.toString()

        assertTrue(text, text.startsWith("Cut along y through the ROI centre at 12 px."))
        val swatches = (legend as Spanned).getSpans(0, legend.length, ImageSpan::class.java)
        assertEquals(3, swatches.size)
        // Each swatch sits right before its label.
        listOf("Exx", "Eyy", "Exy").forEachIndexed { i, label ->
            val end = legend.getSpanEnd(swatches[i])
            assertEquals(" $label", text.substring(end, end + label.length + 1))
        }
    }

    @Test
    fun `the probe reading names its coordinates' unit`() {
        // Readiness AE1-112: '(924, 572)' had no unit beside 'U: 0.49980 px'.
        val text = viewer().getString(R.string.probe_reading_fmt, "U", "0.49980", "px", 924, 572)
        assertEquals("U: 0.49980 px\n(924, 572) px", text)
    }
}
