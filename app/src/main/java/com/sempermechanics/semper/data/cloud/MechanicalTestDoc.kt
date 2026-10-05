package com.sempermechanics.semper.data.cloud

import com.sempermechanics.semper.data.BeamEdgeTaps
import com.sempermechanics.semper.data.CurveCorrection
import com.sempermechanics.semper.data.SpecimenGeometry
import com.sempermechanics.semper.data.loadOfFrame
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.util.OptDoubleSerializer
import com.sempermechanics.semper.util.OptObjectSerializer
import com.sempermechanics.semper.util.OptStringSerializer
import kotlinx.serialization.Serializable

/**
 * The `test` object of a backup's `metadata.json` (schema `/4` and later): the
 * mechanical test behind the session, its specimen and where its loads came
 * from. Absent on a plain DIC session, so that session's file is what it was
 * before test types existed.
 *
 * - `/4` added `test` and each frame's `loadN` ([SessionMetadataDoc.Frame.loadN]);
 * - `/5` added `test.geometry` (bending's span, width and thickness);
 * - `/6` added `test.geometry.loadPoint` (bending's edge taps, reference px).
 *
 * The deflection and curve corrections ride the same object since ADR-013;
 * each is written only when set, so an older file stays byte-identical.
 */
@Serializable
data class MechanicalTestDoc(
    @Serializable(with = OptStringSerializer::class) val type: String? = null,
    @Serializable(with = OptDoubleSerializer::class) val crossSectionMm2: Double? = null,
    /** `x` or `y`: the strain the stress–strain curve reads. */
    @Serializable(with = OptStringSerializer::class) val loadAxis: String? = null,
    @Serializable(with = OptStringSerializer::class) val loadUnit: String? = null,
    @Serializable(with = OptStringSerializer::class) val loadSource: String? = null,
    @Serializable(with = OptStringSerializer::class) val loadMapping: String? = null,
    @Serializable(with = GeometrySerializer::class) val geometry: Geometry? = null,
    @Serializable(with = CorrectionSerializer::class) val curveCorrection: Correction? = null,
) {

    /** The entered dimensions only (a blank one is left out), the deflection correction and the taps. */
    @Serializable
    data class Geometry(
        @Serializable(with = OptDoubleSerializer::class) val spanMm: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val widthMm: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val thicknessMm: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val deflectionScale: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val deflectionBiasMm: Double? = null,
        @Serializable(with = LoadPointSerializer::class) val loadPoint: LoadPoint? = null,
    )

    /** Bending's two edge taps on the reference, in its pixels. */
    @Serializable
    data class LoadPoint(
        @Serializable(with = OptDoubleSerializer::class) val topX: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val topY: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val bottomX: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val bottomY: Double? = null,
    )

    /** Tensile's hand-entered scale and bias (Results' Adjust curve). */
    @Serializable
    data class Correction(
        @Serializable(with = OptDoubleSerializer::class) val strainScale: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val strainBiasMilli: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val stressScale: Double? = null,
        @Serializable(with = OptDoubleSerializer::class) val stressBiasMPa: Double? = null,
    )

    /** The geometry as a restore takes it: a negative dimension is none, and taps count only as a pair. */
    fun restoredGeometry(): SpecimenGeometry {
        val g = geometry ?: return SpecimenGeometry.NONE
        fun mm(value: Double?) = (value ?: 0.0).toFloat().coerceAtLeast(0f)
        return SpecimenGeometry(
            spanMm = mm(g.spanMm),
            widthMm = mm(g.widthMm),
            thicknessMm = mm(g.thicknessMm),
            loadPoint = restoredTaps(g.loadPoint),
            deflectionScale = (g.deflectionScale ?: 1.0).toFloat(),
            deflectionBiasMm = (g.deflectionBiasMm ?: 0.0).toFloat(),
        )
    }

    /** Tensile's scale and bias from a backup that has one; anything unusable is none. */
    fun restoredCorrection(): CurveCorrection {
        val c = curveCorrection ?: return CurveCorrection.NONE
        return CurveCorrection.of(
            (c.strainScale ?: 1.0).toFloat(),
            (c.strainBiasMilli ?: 0.0).toFloat(),
            (c.stressScale ?: 1.0).toFloat(),
            (c.stressBiasMPa ?: 0.0).toFloat(),
        )
    }

    companion object {
        /**
         * What the uploader writes for [record], or null when no test was
         * chosen, so an untyped session's metadata is what it was before.
         */
        fun of(record: SessionRecord): MechanicalTestDoc? {
            if (record.testType.isBlank()) return null
            return MechanicalTestDoc(
                type = record.testType,
                crossSectionMm2 = record.crossSectionMm2.toDouble(),
                loadAxis = if (record.loadAxisX) "x" else "y",
                loadUnit = "N",
                loadSource = record.loadSource,
                loadMapping = record.loadMapping,
                geometry = geometryOf(record.geometry),
                curveCorrection = record.curveCorrection.takeUnless { it.isNone }?.let {
                    Correction(
                        strainScale = it.strainScale.toDouble(),
                        strainBiasMilli = it.strainBiasMilli.toDouble(),
                        stressScale = it.stressScale.toDouble(),
                        stressBiasMPa = it.stressBiasMPa.toDouble(),
                    )
                },
            )
        }

        /** The load of frame [index] as the uploader writes it: only when every frame has one and this one matched. */
        fun frameLoadN(record: SessionRecord, index: Int): Double? =
            if (record.hasMachineLoads) record.loadsN.loadOfFrame(index)?.toDouble() else null

        /** The entered dimensions only, or null when none; every other test stays byte-identical to `/4`. */
        private fun geometryOf(geometry: SpecimenGeometry): Geometry? {
            if (geometry.isNone) return null
            fun entered(value: Float) = value.takeIf { it > 0f }?.toDouble()
            val correction = geometry.deflectionCorrection.takeUnless { it.isNone }
            val taps = geometry.loadPoint.takeIf { it.isSet }
            return Geometry(
                spanMm = entered(geometry.spanMm),
                widthMm = entered(geometry.widthMm),
                thicknessMm = entered(geometry.thicknessMm),
                deflectionScale = correction?.scale?.toDouble(),
                deflectionBiasMm = correction?.biasMm?.toDouble(),
                loadPoint = taps?.let {
                    LoadPoint(it.topX.toDouble(), it.topY.toDouble(), it.bottomX.toDouble(), it.bottomY.toDouble())
                },
            )
        }

        /** The `/6` edge taps, or none when absent or not two distinct edges. */
        private fun restoredTaps(point: LoadPoint?): BeamEdgeTaps {
            if (point == null) return BeamEdgeTaps.NONE
            fun px(value: Double?) = (value ?: 0.0).toFloat().takeIf { it.isFinite() } ?: 0f
            val taps = BeamEdgeTaps(px(point.topX), px(point.topY), px(point.bottomX), px(point.bottomY))
            return if (taps.isSet) taps else BeamEdgeTaps.NONE
        }
    }

    internal object GeometrySerializer : OptObjectSerializer<Geometry>(Geometry.serializer())

    internal object LoadPointSerializer : OptObjectSerializer<LoadPoint>(LoadPoint.serializer())

    internal object CorrectionSerializer : OptObjectSerializer<Correction>(Correction.serializer())

    internal object OptSerializer : OptObjectSerializer<MechanicalTestDoc>(serializer())
}

/**
 * [this] with the mechanical test [doc] carries, from a `/4` or later backup.
 * Pre-`/4` backups have no `test` object, so every field stays at its "no
 * test" default.
 */
internal fun SessionRecord.withRestoredTest(doc: SessionMetadataDoc): SessionRecord {
    val test = doc.test ?: return this
    return copy(
        testType = test.type.orEmpty(),
        crossSectionMm2 = (test.crossSectionMm2 ?: 0.0).toFloat(),
        loadAxisX = (test.loadAxis ?: "x") != "y",
        loadsN = restoredLoads(doc, defNames.size),
        loadSource = test.loadSource.orEmpty(),
        loadMapping = test.loadMapping.orEmpty(),
        geometry = test.restoredGeometry(),
        curveCorrection = test.restoredCorrection(),
    )
}

/**
 * One load per frame, NaN where a frame has no `loadN` (the time match found
 * no log row for it). A session where no frame has one restores without loads.
 */
private fun restoredLoads(doc: SessionMetadataDoc, frameCount: Int): List<Float> {
    val frames = doc.frames.orEmpty()
    if (frameCount == 0 || frames.size != frameCount) return emptyList()
    val loads = frames.map { (it.loadN ?: Double.NaN).toFloat() }
    return if (loads.none { it.isFinite() }) emptyList() else loads
}
