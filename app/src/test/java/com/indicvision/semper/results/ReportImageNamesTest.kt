package com.indicvision.semper.results

import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.report.ReportImageNames
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cloud-backup PDFs used to print "Ref: Baseline" / "Def: Frame_3" where the
 * on-device report prints the user's file names. Both now take their names
 * from here, off the same session fields.
 */
class ReportImageNamesTest {

    private fun record(defNames: List<String>, sweepLabels: List<String> = emptyList()) = SessionRecord(
        id = "s", name = "s", createdAt = 0, updatedAt = 0, frameCount = defNames.size,
        subset = 21, step = 5, strainWindow = 15,
        imgW = 64, imgH = 64, roiX = 0, roiY = 0, roiW = 64, roiH = 64,
        refPath = "", refName = "IMG_0001.JPG", sessionDir = "",
        defNames = defNames,
        sweepSteps = if (sweepLabels.isEmpty()) emptyList() else sweepLabels.map { 5 },
        sweepLabels = sweepLabels,
    )

    @Test
    fun `the reference is named as the user named it`() {
        assertEquals("IMG_0001.JPG", ReportImageNames.reference("IMG_0001.JPG"))
        assertEquals("reference.png", ReportImageNames.reference(""))
    }

    @Test
    fun `a frame is named after its own image, with Frame_N only as the fallback`() {
        val names = record(listOf("IMG_0002.JPG", "IMG_0003.JPG", "")).frameNames

        assertEquals("IMG_0003.JPG", ReportImageNames.deformed(names, 1))
        assertEquals("Frame_3", ReportImageNames.deformed(names, 2))
        assertEquals("Frame_4", ReportImageNames.deformed(names, 3))
    }

    @Test
    fun `a sweep's frames are its combinations, as in the viewer`() {
        val sweep = record(listOf("IMG_0002.JPG"), sweepLabels = listOf("S21/5", "S31/5"))

        assertEquals(listOf("S21/5", "S31/5"), sweep.frameNames)
        assertEquals("S31/5", ReportImageNames.deformed(sweep.frameNames, 1))
    }

    @Test
    fun `the specimen drops the extension`() {
        assertEquals("IMG_0001", ReportImageNames.specimen("IMG_0001.JPG"))
        assertEquals("Batch Analysis", ReportImageNames.specimen(""))
    }
}
