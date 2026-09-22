package com.indicvision.semper.ui.viewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.DicKeys
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.ui.analysis.VsgLatticeActivity
import com.indicvision.semper.ui.home.SessionOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The viewer's Intent contract, which two entry points write and two
 * Activities read.
 *
 * The failure this guards against is silent by construction: a key added on
 * the post-run path and missed on the Home path gives a viewer that works
 * after an analysis and quietly renders defaults after a reopen, because a
 * missing extra *is* a default. Asserting the two key sets against each other
 * is the only thing that fails when they drift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerArgsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun args(sweep: ViewerSweepArgs? = null) = ViewerArgs(
        imgW = 1920,
        imgH = 1080,
        step = 5,
        refName = "ref.png",
        refPath = "/sessions/s1/ref.png",
        batchDirPath = "/sessions/s1",
        frameNames = listOf("f1.png", "f2.png"),
        stopCode = 0,
        plannedFrames = 2,
        sessionId = "cloud-1",
        sessionLocalId = "local-1",
        subsetSize = 41,
        strainWindow = 15,
        engineStats = floatArrayOf(1f, 2f),
        roiX = 10,
        roiY = 20,
        roiW = 300,
        roiH = 400,
        sweep = sweep,
    )

    private fun record(
        sweepSteps: List<Int> = emptyList(),
        testType: String = "",
        loadsN: List<Float> = emptyList(),
    ) = SessionRecord(
        id = "local-1",
        name = "Session 1",
        createdAt = 0L,
        updatedAt = 0L,
        frameCount = 2,
        subset = 41,
        step = 5,
        strainWindow = 15,
        imgW = 1920,
        imgH = 1080,
        roiX = 10,
        roiY = 20,
        roiW = 300,
        roiH = 400,
        refPath = "/sessions/s1/ref.png",
        refName = "ref.png",
        sessionDir = "/sessions/s1",
        defNames = listOf("f1.png", "f2.png"),
        engineStats = listOf(1f, 2f),
        sweepSubsets = if (sweepSteps.isEmpty()) emptyList() else listOf(41, 51),
        sweepSteps = sweepSteps,
        sweepStrainWindows = if (sweepSteps.isEmpty()) emptyList() else listOf(15, 21),
        sweepLabels = if (sweepSteps.isEmpty()) emptyList() else listOf("41/5", "51/7"),
        testType = testType,
        crossSectionMm2 = if (testType.isBlank()) 0f else 12.5f,
        loadAxisX = testType.isBlank(),
        loadsN = loadsN,
    )

    @Test
    fun `both entry points write the same extras, bar the two only a fresh run has`() {
        val fromHome = SessionOpenHelper.intentFor(context, record()).extras!!.keySet()
        val fromRun = args()
            .copy(defPath = "/tmp/def.png", defFilePaths = listOf("/tmp/f1.png"))
            .toIntent(context)
            .extras!!
            .keySet()

        // The post-run launch adds the just-analysed run's temp paths. Home has
        // no use for them: its frames are the copies persisted under the
        // session dir, which the viewer prefers over these anyway.
        assertEquals(setOf(DicKeys.DEF_PATH, DicKeys.DEF_FILE_PATHS), fromRun - fromHome)
        assertEquals(emptySet<String>(), fromHome - fromRun)
    }

    @Test
    fun `a sweep opens the lattice and carries its per-frame settings`() {
        val sweep = ViewerSweepArgs(
            subsets = listOf(41, 51),
            steps = listOf(5, 7),
            strainWindows = listOf(15, 21),
            lineCutHorizontal = false,
            skippedJson = "[]",
        )
        val intent = args(sweep).toIntent(context)

        assertEquals(VsgLatticeActivity::class.java.name, intent.component!!.className)
        assertTrue(intArrayOf(41, 51).contentEquals(intent.getIntArrayExtra(DicKeys.SWEEP_SUBSETS)))
        assertTrue(intArrayOf(5, 7).contentEquals(intent.getIntArrayExtra(DicKeys.SWEEP_STEPS)))
        assertTrue(
            intArrayOf(15, 21)
                .contentEquals(intent.getIntArrayExtra(DicKeys.SWEEP_STRAIN_WINS)),
        )
        assertFalse(intent.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true))
        assertEquals("[]", intent.getStringExtra(DicKeys.SWEEP_SKIPPED))
    }

    @Test
    fun `an ordinary analysis opens the viewer and writes no sweep keys`() {
        val intent = args().toIntent(context)

        assertEquals(ResultViewerActivity::class.java.name, intent.component!!.className)
        assertNull(intent.getIntArrayExtra(DicKeys.SWEEP_SUBSETS))
        assertNull(intent.getStringExtra(DicKeys.SWEEP_SKIPPED))
        // Geometry and identity survive the trip unchanged — the viewer's math
        // and its cloud lookups both read these.
        assertEquals(1920, intent.getIntExtra(DicKeys.IMG_W, 0))
        assertEquals(400, intent.getIntExtra(DicKeys.ROI_H, 0))
        assertEquals("cloud-1", intent.getStringExtra(DicKeys.SESSION_ID))
        assertEquals("local-1", intent.getStringExtra(DicKeys.SESSION_LOCAL_ID))
        assertEquals(listOf("f1.png", "f2.png"), intent.getStringArrayListExtra(DicKeys.DEF_FILE_NAMES))
    }

    @Test
    fun `a sweep session reopened from Home is labelled by combination, not filename`() {
        val intent = SessionOpenHelper.intentFor(context, record(sweepSteps = listOf(5, 7)))

        assertEquals(VsgLatticeActivity::class.java.name, intent.component!!.className)
        assertEquals(listOf("41/5", "51/7"), intent.getStringArrayListExtra(DicKeys.DEF_FILE_NAMES))
    }

    @Test
    fun `an untyped session still registers the mechanical keys, as empty`() {
        val intent = args().toIntent(context)

        assertTrue(intent.hasExtra(DicKeys.TEST_TYPE))
        assertEquals("", intent.getStringExtra(DicKeys.TEST_TYPE))
        assertEquals(0f, intent.getFloatExtra(DicKeys.CROSS_SECTION_MM2, -1f), 0f)
        assertTrue(intent.getBooleanExtra(DicKeys.LOAD_AXIS_X, false))
        assertEquals(0, intent.getFloatArrayExtra(DicKeys.LOADS_N)!!.size)
        assertEquals(
            SpecimenGeometry.NONE,
            SpecimenGeometry.fromArray(intent.getFloatArrayExtra(DicKeys.SPECIMEN_GEOMETRY)),
        )
    }

    @Test
    fun `a typed session carries its test and loads from both entry points`() {
        val fromHome = SessionOpenHelper.intentFor(
            context,
            record(testType = "compression", loadsN = listOf(0f, -950f)),
        )
        val fromRun = args()
            .copy(
                testType = "compression",
                crossSectionMm2 = 12.5f,
                loadAxisX = false,
                loadsN = floatArrayOf(0f, -950f),
            )
            .toIntent(context)

        for (intent in listOf(fromHome, fromRun)) {
            assertEquals("compression", intent.getStringExtra(DicKeys.TEST_TYPE))
            assertEquals(12.5f, intent.getFloatExtra(DicKeys.CROSS_SECTION_MM2, 0f), 1e-4f)
            assertFalse(intent.getBooleanExtra(DicKeys.LOAD_AXIS_X, true))
            assertTrue(floatArrayOf(0f, -950f).contentEquals(intent.getFloatArrayExtra(DicKeys.LOADS_N)))
        }
    }

    @Test
    fun `bending geometry rides the intent from both entry points`() {
        val geometry = SpecimenGeometry(spanMm = 80f, widthMm = 10f, thicknessMm = 4f)
        val fromHome = SessionOpenHelper.intentFor(
            context,
            record(testType = "bending", loadsN = listOf(0f, 100f)).copy(geometry = geometry),
        )
        val fromRun = args().copy(testType = "bending", geometry = geometry).toIntent(context)

        for (intent in listOf(fromHome, fromRun)) {
            assertEquals(geometry, SpecimenGeometry.fromArray(intent.getFloatArrayExtra(DicKeys.SPECIMEN_GEOMETRY)))
        }
    }

    @Test
    fun `loads that do not cover every frame are not handed to the viewer`() {
        val intent = SessionOpenHelper.intentFor(
            context,
            record(testType = "tensile", loadsN = listOf(0f)),
        )

        assertEquals("tensile", intent.getStringExtra(DicKeys.TEST_TYPE))
        assertEquals(0, intent.getFloatArrayExtra(DicKeys.LOADS_N)!!.size)
    }
}
