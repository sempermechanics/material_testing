package com.indicvision.semper.data

private const val TENSILE_STRAIN_WINDOW_POINTS = 5
private const val BENDING_STRAIN_WINDOW_POINTS = 9

/**
 * The mechanical test a session's frames were photographed under.
 *
 * [wireName] is what `index.json`, `metadata.json` and Intent extras carry —
 * lowercase and stable, never the enum name, so a rename here cannot orphan a
 * stored session. [hasMachineLoad] marks the tests whose wizard takes the
 * testing machine's load log, and so can produce a stress–strain curve — both
 * types today, each with its own stress model (see `report/StressStrain.Model`)
 * and dimensions ([SpecimenGeometry] for bending, the cross-section for
 * tensile). The flag stays so a type without a load log can be added without
 * touching the wizard.
 *
 * [defaultStrainWindow] is where the wizard's strain-window slider starts
 * (and what Reset returns it to), in data points; the VSG in pixels follows
 * from the step (`VsgStudy.vsgFor`). At the default step of 5 px, tensile's
 * 5 points is a 21 px VSG and bending's 9 points is 41 px. Bending reads its
 * results from displacement at the load point, so its strain only draws the
 * maps; a wider window makes them far less noisy. On the published PMMA beam
 * (docs/app/REAL_WORLD_VALIDATION.md, case 2) strain RMSE against the
 * authors' DIC fell from about 800–1,300 µε at a 15 px VSG to 250–700 µε at
 * 45 px.
 *
 * Compression and torsion are deliberately not here: the app ships tensile and
 * bending for now. Their wire names stay reserved, so a session stored by an
 * earlier build reads as "no type" rather than being mistaken for another test.
 */
enum class TestType(val wireName: String, val hasMachineLoad: Boolean, val defaultStrainWindow: Int) {
    TENSILE("tensile", true, TENSILE_STRAIN_WINDOW_POINTS),
    BENDING("bending", true, BENDING_STRAIN_WINDOW_POINTS),
    ;

    companion object {
        /**
         * The type behind a stored [wireName], or null for a blank or unknown
         * one — a session recorded before test types existed has no type, and
         * every reader treats that as "show nothing" rather than a default.
         */
        fun fromWire(name: String?): TestType? =
            entries.firstOrNull { it.wireName == name }
    }
}
