package com.indicvision.semper.data

/**
 * The mechanical test a session's frames were photographed under.
 *
 * [wireName] is what `index.json`, `metadata.json` and Intent extras carry —
 * lowercase and stable, never the enum name, so a rename here cannot orphan a
 * stored session. [hasMachineLoad] marks the tests whose wizard takes the
 * testing machine's load log, and so can produce a stress–strain curve.
 * Bending and torsion only record which test ran for now; the seam for their
 * own inputs is [MechanicalTestInputs].
 */
enum class TestType(val wireName: String, val hasMachineLoad: Boolean) {
    TENSILE("tensile", true),
    COMPRESSION("compression", true),
    BENDING("bending", false),
    TORSION("torsion", false),
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
