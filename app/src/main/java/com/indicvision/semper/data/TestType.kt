package com.indicvision.semper.data

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
 * Compression and torsion are deliberately not here: the app ships tensile and
 * bending for now. Their wire names stay reserved, so a session stored by an
 * earlier build reads as "no type" rather than being mistaken for another test.
 */
enum class TestType(val wireName: String, val hasMachineLoad: Boolean) {
    TENSILE("tensile", true),
    BENDING("bending", true),
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
