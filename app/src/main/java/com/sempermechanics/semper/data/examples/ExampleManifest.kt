package com.sempermechanics.semper.data.examples

import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One bundled example set: `assets/examples/<id>/example.json` (ADR-031).
 * The images, the load log and the ground truth sit beside it in the same
 * folder, under the names it lists; [credit] is what the set's licence asks
 * the app to show.
 *
 * @property outcome [OUTCOME_WORKS], or [OUTCOME_EXPECTED_TO_FAIL] for a set
 *   kept to show what photos that cannot be tracked do.
 * @property folderName the folder Save to phone writes under `Download/Semper examples/`.
 * @property imageSize the reference's width and height in pixels.
 * @property roi x, y, width, height in reference pixels.
 */
@Serializable
data class ExampleManifest(
    val schema: Int = 1,
    val id: String,
    val order: Int,
    val title: String,
    val summary: String,
    @SerialName("test_type") val testTypeWire: String,
    val outcome: String = OUTCOME_WORKS,
    @SerialName("folder_name") val folderName: String,
    @SerialName("image_size") val imageSize: List<Int>,
    val reference: String,
    val deformed: List<String>,
    val roi: List<Int>,
    @SerialName("load_log") val loadLog: LoadLog? = null,
    val specimen: Specimen? = null,
    @SerialName("tap_hint") val tapHint: TapHint? = null,
    val expected: List<Expected> = emptyList(),
    @SerialName("truth_file") val truthFile: String? = null,
    @SerialName("extra_files") val extraFiles: List<String> = emptyList(),
    val credit: Credit,
) {
    val testType: TestType? get() = TestType.fromWire(testTypeWire)

    val expectedToFail: Boolean get() = outcome == OUTCOME_EXPECTED_TO_FAIL

    val referenceSize: ImageSize get() = ImageSize(imageSize[0], imageSize[1])

    val roiRect: Roi get() = requireNotNull(Roi.fromXywh(roi.toIntArray())) { "Example $id: roi is not x, y, w, h" }

    /**
     * Every file of the set, in the order Save to phone writes them: the
     * notes first (README, credit, licence), then the photos, the load log,
     * the ground truth and the rest. The provider serves only these.
     */
    val files: List<String>
        get() = (
            listOf(README, CREDIT) +
                listOfNotNull(credit.licenceFile, reference) +
                deformed +
                listOfNotNull(loadLog?.file, truthFile) +
                extraFiles +
                MANIFEST
            ).distinct()

    @Serializable
    data class LoadLog(val file: String, val unit: String)

    /**
     * The dimensions the wizard is pre-filled with: [crossSectionMm2] and
     * [loadAxis] for tensile, the beam's for bending. [spanIsPlaceholder]
     * marks a span the dataset does not publish.
     */
    @Serializable
    data class Specimen(
        @SerialName("cross_section_mm2") val crossSectionMm2: Float = 0f,
        @SerialName("load_axis") val loadAxis: String = "x",
        @SerialName("span_mm") val spanMm: Float = 0f,
        @SerialName("width_mm") val widthMm: Float = 0f,
        @SerialName("thickness_mm") val thicknessMm: Float = 0f,
        @SerialName("span_is_placeholder") val spanIsPlaceholder: Boolean = false,
    ) {
        val loadAxisX: Boolean get() = loadAxis != "y"

        /** Bending's dimensions with no taps: the student taps the beam's edges. */
        val geometry: SpecimenGeometry
            get() = if (spanMm > 0f || thicknessMm > 0f) {
                SpecimenGeometry(spanMm, widthMm, thicknessMm, BeamEdgeTaps.NONE)
            } else {
                SpecimenGeometry.NONE
            }
    }

    /** Where to tap the beam's edges on the reference, in pixels: shown, never set. */
    @Serializable
    data class TapHint(
        val x: Int,
        @SerialName("top_y") val topY: Int,
        @SerialName("bottom_y") val bottomY: Int,
    )

    /**
     * One expected result. [source] is [SOURCE_DATASET] for the dataset's own
     * measurement, [SOURCE_APP] for an earlier run of this app.
     */
    @Serializable
    data class Expected(
        val label: String,
        val value: String,
        val source: String,
        val note: String? = null,
    )

    /**
     * The attribution the set's licence asks for. [licenceFile] names the
     * licence text that travels with the files (BSD-3-Clause asks for it);
     * a set under CC BY 4.0 links [licenceUrl] instead.
     */
    @Serializable
    data class Credit(
        val creators: String,
        val title: String,
        val year: Int,
        val publisher: String,
        val doi: String? = null,
        val url: String,
        val licence: String,
        @SerialName("licence_url") val licenceUrl: String,
        @SerialName("licence_file") val licenceFile: String? = null,
        val changes: String,
    )

    companion object {
        const val OUTCOME_WORKS = "works"
        const val OUTCOME_EXPECTED_TO_FAIL = "expected_to_fail"
        const val SOURCE_DATASET = "dataset"
        const val SOURCE_APP = "app"
        const val README = "README.md"
        const val CREDIT = "CREDIT.txt"
        const val MANIFEST = "example.json"
    }
}
