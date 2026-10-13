package com.sempermechanics.semper.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.UploadProgressSampler
import com.sempermechanics.semper.data.cloud.restore.RestoreUnpacker
import com.sempermechanics.semper.data.net.ArtifactRoles
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionZip
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
 * The reference has its own archive entry, `ref/Reference.png`, so a deformed
 * frame the user named `Reference.png` backs up and restores as a frame (TD-163).
 * Staging runs for real here, from a staging dir whose reports are already
 * baked, so no report pass runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadStagingReferenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private val referenceBytes = byteArrayOf(1, 1, 1, 1)
    private val frameBytes = byteArrayOf(2, 2, 2)

    /** A one-frame analysis whose only frame is named exactly like the reference entry. */
    private fun record(): SessionRecord {
        val dir = tmp.newFolder("session")
        val layout = SessionLayout(dir)
        layout.referencePng.writeBytes(referenceBytes)
        layout.rawDeformedDir.mkdirs()
        layout.rawDeformed(SessionZip.REFERENCE_NAME).writeBytes(frameBytes)
        SessionPaths.frameDat(dir, 0).writeBytes(ByteArray(32))
        return SessionRecord(
            id = "local-1", name = "n", createdAt = 1L, updatedAt = 1L, frameCount = 1,
            subset = 41, step = 5, strainWindow = 21,
            imgW = 64, imgH = 64, roiX = 0, roiY = 0, roiW = 64, roiH = 64,
            refPath = layout.referencePng.path, refName = "ref.png", sessionDir = dir.path,
            defNames = listOf(SessionZip.REFERENCE_NAME),
        )
    }

    /** What the report pass would have left, so [UploadStaging.stage] goes straight to the archives. */
    private fun bakeReports(staging: UploadStaging) {
        val s = staging.layout
        s.analysisCsv.writeText("image\nReference.png\n")
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
        val outcome = staging.stage(UploadProgressSampler(record.id, "prepare", 0))
        (outcome as StagingOutcome.Ready).files
    }

    private fun bundleOf(files: List<UploadArtifact>): File = files.single { it.role == ArtifactRoles.BUNDLE }.file

    @Test
    fun `a frame named Reference_png and the reference both reach Session_zip`() {
        val zip = bundleOf(stage(record(), bake = true))

        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("ref/Reference.png", "raw/Reference.png", "dat/frame_0000.dat"), names)
            assertArrayEquals(referenceBytes, zf.getInputStream(zf.getEntry("ref/Reference.png")).readBytes())
            assertArrayEquals(frameBytes, zf.getInputStream(zf.getEntry("raw/Reference.png")).readBytes())
        }
    }

    @Test
    fun `the backup restores both images intact`() {
        val zip = bundleOf(stage(record(), bake = true))
        val restored = SessionLayout(tmp.newFolder("restored")).apply { rawDeformedDir.mkdirs() }

        val refPath = RestoreUnpacker.unpackBundle(zip, restored)

        assertEquals(restored.referencePng.absolutePath, refPath)
        assertArrayEquals(referenceBytes, restored.referencePng.readBytes())
        assertArrayEquals(frameBytes, restored.rawDeformed(SessionZip.REFERENCE_NAME).readBytes())
    }

    @Test
    fun `a second staging run declares byte-identical files`() {
        val record = record()
        val first = stage(record, bake = true).associate { it.name to Digests.sha256Hex(it.file) }

        val second = stage(record, bake = false)

        assertEquals(first, second.associate { it.name to Digests.sha256Hex(it.file) })
        assertTrue(second.any { it.role == ArtifactRoles.BUNDLE })
        second.filter { it.sha256Hex != null }.forEach { assertEquals(first[it.name], it.sha256Hex) }
    }
}
