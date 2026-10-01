package com.indicvision.semper.field

import android.content.Intent
import com.indicvision.semper.navigation.DicKeys
import org.json.JSONObject

/**
 * Pixel size of a reference image (or of a deformed frame, which must match it).
 *
 * Today the same pair travels as `realRefWidth` / `realRefHeight` on the wizard's
 * view model, `imgW` / `imgH` on the session record and viewer, the
 * `IMG_W` / `IMG_H` and `IMAGE_WIDTH` / `IMAGE_HEIGHT` Intent extras, the
 * `imageWidth` / `imageHeight` keys of `metadata.json`, and `Pair<Int, Int>`
 * from the decoders. This is a view over those fields: it never changes what
 * any of them store. A size of zero means "not measured yet", which is why the
 * constructor accepts it.
 */
data class ImageSize(val width: Int, val height: Int) {

    /** True when both sides are measured (positive); the wizard's `w > 0 && h > 0` guard. */
    val isKnown: Boolean get() = width > 0 && height > 0

    /** The `width to height` pair the decoders and frame-size maps use. */
    fun toPair(): Pair<Int, Int> = width to height

    /** Writes `imageWidth` / `imageHeight` onto a `metadata.json` engine object, as `engineJson` does. */
    fun putInto(engine: JSONObject): JSONObject = engine
        .put(JSON_WIDTH, width)
        .put(JSON_HEIGHT, height)

    companion object {
        /** No reference loaded: the wizard's reset state and every reader's default. */
        val UNKNOWN = ImageSize(0, 0)

        /** From a decoder's `width to height`. */
        fun of(pair: Pair<Int, Int>): ImageSize = ImageSize(pair.first, pair.second)

        /** From a `metadata.json` engine object; a missing key reads 0, as `CloudRestore.recordFrom` does. */
        fun fromEngineJson(engine: JSONObject): ImageSize =
            ImageSize(engine.optInt(JSON_WIDTH, 0), engine.optInt(JSON_HEIGHT, 0))

        /** Key of the width in `metadata.json`'s `engine` object. */
        const val JSON_WIDTH = "imageWidth"

        /** Key of the height in `metadata.json`'s `engine` object. */
        const val JSON_HEIGHT = "imageHeight"
    }
}

/**
 * The two Intent-extra pairs an [ImageSize] travels under. The key strings are
 * the existing [DicKeys]; an Intent already in a back stack keeps reading.
 */
enum class ImageSizeExtras(val widthKey: String, val heightKey: String) {
    /** Viewer launch (`ViewerArgs`): [DicKeys.IMG_W] / [DicKeys.IMG_H]. */
    VIEWER(DicKeys.IMG_W, DicKeys.IMG_H),

    /** Wizard → ROI editor: [DicKeys.IMAGE_WIDTH] / [DicKeys.IMAGE_HEIGHT]. */
    ROI_EDITOR(DicKeys.IMAGE_WIDTH, DicKeys.IMAGE_HEIGHT),
    ;

    /** Puts [size] as two `Int` extras. */
    fun put(intent: Intent, size: ImageSize): Intent = intent
        .putExtra(widthKey, size.width)
        .putExtra(heightKey, size.height)

    /** Reads both extras; an absent one reads [default]'s side, as `getIntExtra(key, 0)` does today. */
    fun get(intent: Intent, default: ImageSize = ImageSize.UNKNOWN): ImageSize = ImageSize(
        intent.getIntExtra(widthKey, default.width),
        intent.getIntExtra(heightKey, default.height),
    )
}
