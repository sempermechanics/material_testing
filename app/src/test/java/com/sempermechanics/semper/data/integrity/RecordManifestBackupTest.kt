package com.sempermechanics.semper.data.integrity

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.StagingOutcome
import com.sempermechanics.semper.data.UploadArtifact
import com.sempermechanics.semper.data.UploadStaging
import com.sempermechanics.semper.data.cloud.UploadProgressSampler
import com.sempermechanics.semper.data.cloud.restore.RestoreUnpacker
import com.sempermechanics.semper.data.net.ArtifactRoles
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionRecordSettings
import com.sempermechanics.semper.data.session.SessionZip
import com.sempermechanics.semper.fixtures.SoftwareRecordSigner
import com.sempermechanics.semper.util.Digests
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * The record manifest goes where the results go (ADR-028): into `Session.zip`
 * as `dat/record_manifest.json` (the backend validates roles, so it rides on
 * `dat`), the same bytes on every staging attempt, and back beside the frames
 * on a restore, where it still verifies, on any phone, since it carries its
 * public key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecordManifestBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val signer = SoftwareRecordSigner()

    /** A two-frame analysis whose run wrote a signed manifest. */
    private fun record(): SessionRecord {
        val dir = tmp.newFolder("session")
        val layout = SessionLayout(dir)
        layout.referencePng.writeBytes(byteArrayOf(1, 1, 1, 1))
        layout.rawDeformedDir.mkdirs()
        listOf("a.png", "b.png").forEach { layout.rawDeformed(it).writeBytes(byteArrayOf(2, 2)) }
        repeat(2) { i -> SessionPaths.frameDat(dir, i).writeBytes(ByteArray(64) { (it * (i + 1)).toByte() }) }
        RecordManifestWriter.write(dir, SessionRecordSettings(41, 5, 21, 0, 0, 64, 64, false), signer)
        return SessionRecord(
            id = "local-1", name = "n", createdAt = 1L, updatedAt = 1L, frameCount = 2,
            subset = 41, step = 5, strainWindow = 21,
            imgW = 64, imgH = 64, roiX = 0, roiY = 0, roiW = 64, roiH = 64,
            refPath = layout.referencePng.path, refName = "ref.png", sessionDir = dir.path,
            defNames = listOf("a.png", "b.png"),
        )
    }

    /** What the report pass would have left, so staging goes straight to the archives. */
    private fun bakeReports(staging: UploadStaging) {
        val s = staging.layout
        s.analysisCsv.writeText("image\na.png\n")
        s.reportsDir.mkdirs()
        File(s.reportsDir, "Master_Report_Frame_1.pdf").writeText("%PDF")
        File(s.processedDir, "Frame_1").mkdirs()
        File(s.processedDir, "Frame_1/exx.png").writeText("png")
        s.bundlesDone.createNewFile()
    }

    private fun stage(record: SessionRecord, bake: Boolean): List<UploadArtifact> = runBlocking {
        val staging = UploadStaging(context, record)
        staging.prepare()
        if (bake) bakeReports(staging)
        (staging.stage(UploadProgressSampler(record.id, "prepare", 0)) as StagingOutcome.Ready).files
    }

    private fun bundleOf(files: List<UploadArtifact>): File = files.single { it.role == ArtifactRoles.BUNDLE }.file

    @Test
    fun `Session_zip carries the manifest under dat, as written`() {
        val record = record()
        val manifest = SessionLayout(File(record.sessionDir)).recordManifest

        ZipFile(bundleOf(stage(record, bake = true))).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf(
                    SessionZip.REFERENCE_ENTRY,
                    "raw/a.png",
                    "raw/b.png",
                    "dat/frame_0000.dat",
                    "dat/frame_0001.dat",
                    "dat/${SessionLayout.MANIFEST}",
                ),
                names,
            )
            val entry = zf.getEntry("dat/${SessionLayout.MANIFEST}")
            assertArrayEquals(manifest.readBytes(), zf.getInputStream(entry).readBytes())
        }
        assertTrue("restore-essential", SessionZip.isRestoreEssential(ArtifactRoles.DAT))
        assertTrue(SessionZip.RESTORE_ENTRY_PREFIXES.any { "dat/${SessionLayout.MANIFEST}".startsWith(it) })
    }

    @Test
    fun `a second staging run declares byte-identical files`() {
        val record = record()
        val first = stage(record, bake = true).associate { it.name to Digests.sha256Hex(it.file) }

        val second = stage(record, bake = false)

        assertEquals(first, second.associate { it.name to Digests.sha256Hex(it.file) })
    }

    @Test
    fun `a restore puts the manifest back beside the frames, and it verifies there`() {
        val zip = bundleOf(stage(record(), bake = true))
        val restored = SessionLayout(tmp.newFolder("restored")).apply { rawDeformedDir.mkdirs() }

        RestoreUnpacker.unpackBundle(zip, restored)

        assertTrue(restored.recordManifest.isFile)
        assertTrue(RecordVerifier.verify(restored.dir) is RecordIntegrity.Verified)
    }

    @Test
    fun `a restored frame that differs from the backed-up one reads Changed`() {
        val zip = bundleOf(stage(record(), bake = true))
        val restored = SessionLayout(tmp.newFolder("restored")).apply { rawDeformedDir.mkdirs() }
        RestoreUnpacker.unpackBundle(zip, restored)

        restored.frameDat(0).writeBytes(ByteArray(64))

        assertEquals(listOf("frame_0000.dat"), (RecordVerifier.verify(restored.dir) as RecordIntegrity.Changed).files)
    }
}
