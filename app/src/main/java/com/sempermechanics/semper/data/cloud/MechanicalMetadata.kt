@file:OptIn(ExperimentalSerializationApi::class)

package com.sempermechanics.semper.data.cloud

import com.sempermechanics.semper.data.BeamEdgeTaps
import com.sempermechanics.semper.data.CurveCorrection
import com.sempermechanics.semper.data.SpecimenGeometry
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.util.OptDoubleSerializer
import com.sempermechanics.semper.util.OptObjectSerializer
import com.sempermechanics.semper.util.OptStringSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * The lab test's part of a backup's `metadata.json` ([SessionMetadataDoc.test]
 * and each frame's `loadN`): written from the [SessionRecord] by
 * [testDocOf], read back by [withTestDoc]. Every field is additive across
 * schema `/4`–`/6` ([SessionUploadMetadata.SCHEMA_MECHANICAL_TEST] and after),
 * so a plain DIC session's file has no `test` at all and an older reader
 * ignores it.
 */

/** The mechanical test: its type, the specimen and where the loads came from. */
@Serializable
data class TestDoc(
    @Serializable(with = OptStringSerializer::class) val type: String? = null,
    @Serializable(with = OptDoubleSerializer::class) val crossSectionMm2: Double? = null,
    /** `x` or `y`: the strain the stress–strain curve uses. */
    @Serializable(with = OptStringSerializer::class) val loadAxis: String? = null,
    @Serializable(with = OptStringSerializer::class) val loadUnit: String? = null,
    @Serializable(with = OptStringSerializer::class) val loadSource: String? = null,
    @Serializable(with = OptStringSerializer::class) val loadMapping: String? = null,
    /** Bending's dimensions (`/5`), only those entered. */
    @Serializable(with = GeometryDocSerializer::class) val geometry: GeometryDoc? = null,
    /** Tensile's scale and bias from Results; absent when none was set. */
    @Serializable(with = CorrectionDocSerializer::class) val curveCorrection: CorrectionDoc? = null,
)

@Serializable
data class GeometryDoc(
    @Serializable(with = OptDoubleSerializer::class) val spanMm: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val widthMm: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val thicknessMm: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val deflectionScale: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val deflectionBiasMm: Double? = null,
    /** The beam's tapped edges in reference pixels (`/6`); written only when both are tapped. */
    @Serializable(with = LoadPointDocSerializer::class) val loadPoint: LoadPointDoc? = null,
)

@Serializable
data class LoadPointDoc(
    @Serializable(with = OptDoubleSerializer::class) val topX: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val topY: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val bottomX: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val bottomY: Double? = null,
)

@Serializable
data class CorrectionDoc(
    @Serializable(with = OptDoubleSerializer::class) val strainScale: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val strainBiasMilli: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val stressScale: Double? = null,
    @Serializable(with = OptDoubleSerializer::class) val stressBiasMPa: Double? = null,
)

internal object TestDocSerializer : OptObjectSerializer<TestDoc>(TestDoc.serializer())

internal object GeometryDocSerializer : OptObjectSerializer<GeometryDoc>(GeometryDoc.serializer())

internal object LoadPointDocSerializer : OptObjectSerializer<LoadPointDoc>(LoadPointDoc.serializer())

internal object CorrectionDocSerializer : OptObjectSerializer<CorrectionDoc>(CorrectionDoc.serializer())

/**
 * The mechanical test behind [record], or null when none was chosen, so an
 * untyped session's file has no `test` at all.
 */
internal fun testDocOf(record: SessionRecord): TestDoc? {
    if (record.testType.isBlank()) return null
    return TestDoc(
        type = record.testType,
        crossSectionMm2 = record.crossSectionMm2.toDouble(),
        loadAxis = if (record.loadAxisX) "x" else "y",
        loadUnit = "N",
        loadSource = record.loadSource,
        loadMapping = record.loadMapping,
        geometry = geometryDocOf(record.geometry),
        curveCorrection = correctionDocOf(record.curveCorrection),
    )
}

/** The entered dimensions only, or null when none: a tensile test's `test` stays as `/4` wrote it. */
private fun geometryDocOf(geometry: SpecimenGeometry): GeometryDoc? {
    if (geometry.isNone) return null
    fun entered(value: Float) = value.toDouble().takeIf { value > 0f }
    val correction = geometry.deflectionCorrection.takeUnless { it.isNone }
    val taps = geometry.loadPoint.takeIf { it.isSet }
    return GeometryDoc(
        spanMm = entered(geometry.spanMm),
        widthMm = entered(geometry.widthMm),
        thicknessMm = entered(geometry.thicknessMm),
        deflectionScale = correction?.scale?.toDouble(),
        deflectionBiasMm = correction?.biasMm?.toDouble(),
        loadPoint = taps?.let {
            LoadPointDoc(it.topX.toDouble(), it.topY.toDouble(), it.bottomX.toDouble(), it.bottomY.toDouble())
        },
    )
}

/** Tensile's hand-entered scale and bias, or null when none was set. */
private fun correctionDocOf(c: CurveCorrection): CorrectionDoc? {
    if (c.isNone) return null
    return CorrectionDoc(
        strainScale = c.strainScale.toDouble(),
        strainBiasMilli = c.strainBiasMilli.toDouble(),
        stressScale = c.stressScale.toDouble(),
        stressBiasMPa = c.stressBiasMPa.toDouble(),
    )
}

/**
 * This row with the mechanical test from a `/4` file, plus the specimen
 * geometry a `/5` one adds; [frameLoadsN] is each frame's `loadN` and
 * [frameCount] the frames the row keeps. Pre-`/4` files have no `test`, so
 * every field stays at its "no test" default.
 */
internal fun SessionRecord.withTestDoc(test: TestDoc?, frameLoadsN: List<Double?>, frameCount: Int): SessionRecord {
    test ?: return this
    return copy(
        testType = test.type.orEmpty(),
        crossSectionMm2 = (test.crossSectionMm2 ?: 0.0).toFloat(),
        loadAxisX = (test.loadAxis ?: "x") != "y",
        loadsN = restoredLoads(frameLoadsN, frameCount),
        loadSource = test.loadSource.orEmpty(),
        loadMapping = test.loadMapping.orEmpty(),
        geometry = test.geometry?.toSpecimenGeometry() ?: SpecimenGeometry.NONE,
        curveCorrection = test.curveCorrection?.toCurveCorrection() ?: CurveCorrection.NONE,
    )
}

/**
 * One load per frame, NaN where a frame has no `loadN` (the time match found
 * no log row for it). A file where no frame has one, or whose frame list does
 * not match the row's frames, restores without loads.
 */
private fun restoredLoads(frameLoadsN: List<Double?>, frameCount: Int): List<Float> {
    if (frameCount == 0 || frameLoadsN.size != frameCount) return emptyList()
    val loads = frameLoadsN.map { (it ?: Double.NaN).toFloat() }
    return if (loads.none { it.isFinite() }) emptyList() else loads
}

/** Bending's dimensions as entered; a missing or negative one is "not entered". */
private fun GeometryDoc.toSpecimenGeometry(): SpecimenGeometry {
    fun mm(value: Double?) = (value ?: 0.0).toFloat().coerceAtLeast(0f)
    return SpecimenGeometry(
        spanMm = mm(spanMm),
        widthMm = mm(widthMm),
        thicknessMm = mm(thicknessMm),
        loadPoint = loadPoint?.toTaps() ?: BeamEdgeTaps.NONE,
        deflectionScale = (deflectionScale ?: 1.0).toFloat(),
        deflectionBiasMm = (deflectionBiasMm ?: 0.0).toFloat(),
    )
}

/** The `/6` edge taps, or none when not two distinct edges. */
private fun LoadPointDoc.toTaps(): BeamEdgeTaps {
    fun px(value: Double?) = (value ?: 0.0).toFloat().takeIf { it.isFinite() } ?: 0f
    val taps = BeamEdgeTaps(px(topX), px(topY), px(bottomX), px(bottomY))
    return if (taps.isSet) taps else BeamEdgeTaps.NONE
}

/** Tensile's scale and bias; anything unusable is none ([CurveCorrection.of]). */
private fun CorrectionDoc.toCurveCorrection(): CurveCorrection = CurveCorrection.of(
    (strainScale ?: 1.0).toFloat(),
    (strainBiasMilli ?: 0.0).toFloat(),
    (stressScale ?: 1.0).toFloat(),
    (stressBiasMPa ?: 0.0).toFloat(),
)
