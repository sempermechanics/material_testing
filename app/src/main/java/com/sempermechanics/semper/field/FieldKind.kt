package com.sempermechanics.semper.field

/**
 * The five fields a result shows, in screen order: where each sits in a
 * `.dat` point ([dataIndex]), what it is called ([label] on the pills, in the
 * CSV and in file names; [title] in the report) and the unit it is printed in.
 *
 * The one table: the viewer's pills, the exports, the summary animation, the
 * CSV and the report all read it, where each used to keep its own copy.
 * [label]s are what CSV headers and GIF / PNG file names are built from, so
 * they never change.
 */
enum class FieldKind(val dataIndex: Int, val label: String, val title: String) {
    U(DicResult.IDX_U, "U", "U Displacement"),
    V(DicResult.IDX_V, "V", "V Displacement"),
    EXX(DicResult.IDX_EXX, "Exx", "Exx Strain"),
    EYY(DicResult.IDX_EYY, "Eyy", "Eyy Strain"),
    EXY(DicResult.IDX_EXY, "Exy", "Exy Shear"),
    ;

    /** Exx, Eyy and Exy: shown in millistrain, their mean taken over absolute values. */
    val isStrain: Boolean get() = DicResult.isStrainFieldIndex(dataIndex)

    /** Raw value → display units ([unit]). */
    val multiplier: Float get() = DicResult.strainMultiplier(dataIndex)

    /** The display unit: "mε" for a strain, "px" for a displacement. */
    val unit: String get() = unitOf(dataIndex)

    companion object {
        const val STRAIN_UNIT = "mε"
        const val DISPLACEMENT_UNIT = "px"

        /** The field stored at [dataIndex], or null for x, y or ZNSSD. */
        fun of(dataIndex: Int): FieldKind? = entries.firstOrNull { it.dataIndex == dataIndex }

        /** The display unit of the value at [dataIndex]; "px" for anything not a strain. */
        fun unitOf(dataIndex: Int): String =
            if (DicResult.isStrainFieldIndex(dataIndex)) STRAIN_UNIT else DISPLACEMENT_UNIT
    }
}
