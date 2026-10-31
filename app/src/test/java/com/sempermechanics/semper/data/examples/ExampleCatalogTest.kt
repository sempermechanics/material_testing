package com.sempermechanics.semper.data.examples

import android.app.Application
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.mechanical.LoadCsvParse
import com.sempermechanics.semper.data.mechanical.LoadUnit
import com.sempermechanics.semper.data.mechanical.MachineLoadCsv
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.session.SessionNaming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.imageio.ImageIO

/**
 * The bundled example sets (ADR-031) as they ship: every manifest parses, every
 * file it lists is in the assets, the load log pairs with the frames in the
 * unit it claims, the photos are the size the ROI was drawn on, and every set
 * carries the credit its licence asks for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExampleCatalogTest {

    private val assets: AssetManager = ApplicationProvider.getApplicationContext<Application>().assets
    private val sets = ExampleCatalog.load(assets)

    @Test
    fun `the four sets load in order`() {
        assertEquals(
            listOf("steel_tensile", "pmma_bending", "dice_oht_cfrp", "dice_translation"),
            sets.map { it.id },
        )
    }

    @Test
    fun `every set has a known test type and lists only files it ships`() {
        sets.forEach { set ->
            assertNotNull("${set.id} test type", set.testType)
            set.files.forEach { file ->
                assets.open(ExampleCatalog.path(set.id, file)).use { assertTrue("${set.id}/$file", it.read() >= 0) }
            }
        }
    }

    @Test
    fun `each set's analysis is named for the set, not for a bare ref`() {
        // Try it names the analysis after the reference: DICe's own ref.tiff made a Home row "ref" (Galaxy S22).
        val names = sets.map { SessionNaming.defaultSessionName(it.reference, emptyList()) }
        assertEquals(names.toString(), names.size, names.toSet().size)
        names.zip(sets).forEach { (name, set) ->
            assertTrue("${set.id}: $name", name.startsWith(set.id.removePrefix("dice_").substringBefore('_')))
        }
    }

    @Test
    fun `a lab set's load log has a row per deformed frame in its stated unit`() {
        sets.filter { it.testType?.hasMachineLoad == true }.forEach { set ->
            val log = requireNotNull(set.loadLog) { "${set.id} has no load log" }
            val text = assets.open(ExampleCatalog.path(set.id, log.file)).use { it.readBytes().decodeToString() }
            val parse = MachineLoadCsv.parse(text)
            check(parse is LoadCsvParse.Ok) { "${set.id}: $parse" }
            assertEquals(set.id, set.deformed.size, parse.csv.rows)
            assertEquals(set.id, LoadUnit.entries.single { it.label == log.unit }, parse.csv.unit)
            assertNotNull("${set.id} specimen", set.specimen)
        }
    }

    @Test
    fun `a 2D DIC set has no load log or dimensions`() {
        sets.filter { it.testType == TestType.DIC_2D }.forEach { set ->
            assertEquals(set.id, null, set.loadLog)
            assertEquals(set.id, null, set.specimen)
        }
    }

    @Test
    fun `every photo is the size the ROI was drawn on and the ROI is inside it`() {
        sets.forEach { set ->
            (listOf(set.reference) + set.deformed).forEach { file ->
                assertEquals("${set.id}/$file", set.imageSize, sizeOf(set.id, file))
            }
            val roi = set.roiRect
            assertTrue(set.id, roi.x >= 0 && roi.y >= 0 && roi.w > 0 && roi.h > 0)
            assertTrue(set.id, roi.right <= set.imageSize[0] && roi.bottom <= set.imageSize[1])
        }
    }

    @Test
    fun `every set credits its authors and licence in the app and in its folder`() {
        sets.forEach { set ->
            val c = set.credit
            assertTrue(set.id, c.creators.isNotBlank() && c.title.isNotBlank() && c.changes.isNotBlank())
            assertTrue(set.id, c.licenceUrl.startsWith("https://") && c.url.startsWith("https://"))
            val credit = assets.open(ExampleCatalog.path(set.id, ExampleManifest.CREDIT)).use {
                it.readBytes().decodeToString()
            }
            listOfNotNull(c.creators, c.licence, c.licenceUrl, c.doi).forEach {
                assertTrue("${set.id}: $it", it in credit)
            }
            assertTrue(set.id, "No endorsement" in credit)
        }
    }

    @Test
    fun `the CC BY sets link the licence and the DICe sets carry its text`() {
        sets.forEach { set ->
            when (set.credit.licence) {
                "CC BY 4.0" -> {
                    assertEquals(set.id, "https://creativecommons.org/licenses/by/4.0/", set.credit.licenceUrl)
                    assertNotNull(set.id, set.credit.doi)
                }
                "BSD-3-Clause" -> assertEquals(set.id, "LICENSE.DICe", set.credit.licenceFile)
                else -> error("${set.id}: unexpected licence ${set.credit.licence}")
            }
        }
    }

    @Test
    fun `the DICe files are the engine's own, byte for byte (text up to line endings)`() {
        val samples = File("../engine/examples/samples")
        assumeTrue("engine submodule not checked out", samples.isDirectory)
        val copies = mapOf(
            "dice_oht_cfrp/oht_cfrp_ref.tiff" to "oht_cfrp/ref.tiff",
            "dice_oht_cfrp/oht_cfrp_def.tiff" to "oht_cfrp/def.tiff",
            "dice_oht_cfrp/DICe_solution_01.txt" to "oht_cfrp/DICe_solution_01.txt",
            "dice_oht_cfrp/LICENSE.DICe" to "LICENSE.DICe",
            "dice_translation/translation_ref.tif" to "translation/ref.tif",
            "dice_translation/translation_def.tif" to "translation/def.tif",
            "dice_translation/LICENSE.DICe" to "LICENSE.DICe",
        )
        copies.forEach { (asset, engine) ->
            val bundled = assets.open("${ExampleCatalog.ROOT}/$asset").use { it.readBytes() }
            // Both repos store text as LF; a Windows checkout of the engine may show CRLF.
            val lf = { bytes: ByteArray -> if (asset.endsWith("tif") || asset.endsWith("tiff")) bytes else lf(bytes) }
            assertArrayEquals(asset, lf(File(samples, engine).readBytes()), lf(bundled))
        }
    }

    private fun lf(bytes: ByteArray): ByteArray = bytes.decodeToString().replace("\r\n", "\n").encodeToByteArray()

    @Test
    fun `the licence texts the app shows are bundled`() {
        assertEquals(
            listOf(
                // The short notices that travel with the app: the codecs OpenCV links into
                // libsemper, and the MIT / BSD libraries (TD-57). The long texts are on the website.
                "carotene-BSD-3-Clause.txt",
                "checker-qual-MIT.txt",
                "cpufeatures-Apache-2.0.txt",
                "libjpeg-turbo-IJG-BSD-3-Clause-Zlib.txt",
                "libpng-PNG-2.txt",
                "libtiff-libtiff.txt",
                "libwebp-BSD-3-Clause.txt",
                "protobuf-BSD-3-Clause.txt",
                "zlib-Zlib.txt",
            ),
            assets.list("licences").orEmpty().sorted(),
        )
    }

    @Test
    fun `an unknown set is not found`() {
        assertEquals(null, ExampleCatalog.find(assets, "no_such_set"))
    }

    private fun sizeOf(id: String, file: String): List<Int> =
        assets.open(ExampleCatalog.path(id, file)).use { stream ->
            ImageIO.createImageInputStream(stream).use { input ->
                val reader = ImageIO.getImageReaders(input).next()
                reader.input = input
                listOf(reader.getWidth(0), reader.getHeight(0)).also { reader.dispose() }
            }
        }
}
