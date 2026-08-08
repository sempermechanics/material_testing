// Frame cache: literal cache sizes and early-return guards read clearest inline.
@file:Suppress("MagicNumber", "ReturnCount")

package com.indicvision.semper.ui.viewer

import android.graphics.Bitmap

/**
 * Small LRU for result-viewer scrubbing: recent decoded `.dat` frames plus
 * optional display-scale heatmaps keyed by frame/field/scale bounds.
 */
class ScrubFrameCache(
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val maxHeatmaps: Int = DEFAULT_MAX_HEATMAPS,
) {

    data class HeatKey(
        val frame: Int,
        val field: Int,
        val step: Int,
        val customMin: Float?,
        val customMax: Float?,
    )

    data class HeatEntry(
        val bitmap: Bitmap,
        val minV: Float,
        val maxV: Float,
    )

    private val dataByFrame =
        object : LinkedHashMap<Int, FloatArray>(maxFrames + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FloatArray>?): Boolean =
                size > maxFrames
        }

    private val heatByKey =
        object : LinkedHashMap<HeatKey, HeatEntry>(maxHeatmaps + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HeatKey, HeatEntry>?): Boolean {
                // Drop the entry only — do not recycle here. The ImageView may still
                // be displaying this bitmap; clear() recycles when the viewer exits.
                return size > maxHeatmaps
            }
        }

    @Synchronized
    fun getData(frame: Int): FloatArray? = dataByFrame[frame]

    @Synchronized
    fun putData(frame: Int, data: FloatArray) {
        dataByFrame[frame] = data
    }

    @Synchronized
    fun getHeat(key: HeatKey): HeatEntry? {
        val entry = heatByKey[key] ?: return null
        if (entry.bitmap.isRecycled) {
            heatByKey.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun putHeat(key: HeatKey, entry: HeatEntry) {
        // Overwrite without recycling — the previous bitmap may still be on screen.
        heatByKey[key] = entry
    }

    @Synchronized
    fun clear(except: Bitmap? = null) {
        dataByFrame.clear()
        for (entry in heatByKey.values) {
            val bmp = entry.bitmap
            if (bmp !== except && !bmp.isRecycled) bmp.recycle()
        }
        heatByKey.clear()
    }

    companion object {
        // Keep these small: a single heavy PLC `.dat` is several MB of floats, and
        // the summary range pass + heatmaps share the same 512 MB heap.
        const val DEFAULT_MAX_FRAMES = 2
        const val DEFAULT_MAX_HEATMAPS = 3
    }
}
