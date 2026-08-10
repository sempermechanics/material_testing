// Spatial-index grid math: literal bucket sizes and the tight nearest-neighbour
// scan read clearest inline, so MagicNumber / NestedBlockDepth are suppressed.
@file:Suppress("MagicNumber", "NestedBlockDepth")

package com.indicvision.semper.ui.viewer

import com.indicvision.semper.DicResult
import kotlin.math.floor

/**
 * Grid-bucket nearest-point lookup for inspect mode. Built once per frame load
 * so ACTION_MOVE is O(nearby cells) instead of O(n) over every DIC point.
 */
class PointSpatialIndex private constructor(
    private val data: FloatArray,
    private val cellSize: Float,
    private val buckets: Map<Long, IntArray>,
) {

    fun nearest(physX: Float, physY: Float, searchRadius: Float): Int {
        if (cellSize <= 0f || buckets.isEmpty()) return -1

        val searchRadiusSq = searchRadius * searchRadius
        val minCx = floor((physX - searchRadius) / cellSize).toInt()
        val maxCx = floor((physX + searchRadius) / cellSize).toInt()
        val minCy = floor((physY - searchRadius) / cellSize).toInt()
        val maxCy = floor((physY + searchRadius) / cellSize).toInt()

        var closestIdx = -1
        var minDistSq = Float.MAX_VALUE

        for (cy in minCy..maxCy) {
            for (cx in minCx..maxCx) {
                val cell = buckets[pack(cx, cy)] ?: continue
                for (i in cell) {
                    val dx = data[i] - physX
                    val dy = data[i + 1] - physY
                    val distSq = dx * dx + dy * dy
                    if (distSq < minDistSq && distSq <= searchRadiusSq) {
                        minDistSq = distSq
                        closestIdx = i
                    }
                }
            }
        }
        return closestIdx
    }

    companion object {
        fun build(data: FloatArray, step: Int): PointSpatialIndex {
            val cellSize = step.coerceAtLeast(1).toFloat()

            // Two-pass count-then-fill straight into primitive IntArrays, avoiding the
            // boxed ArrayList<Int> per cell (~one point per step-sized cell, so those
            // boxed lists dominated build allocation). Points are filled in ascending
            // data order — identical intra-bucket order to the old ArrayList.add path,
            // so nearest()'s tie-breaking is unchanged.
            val counts = HashMap<Long, Int>()
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (!DicResult.isAcceptedPoint(corr)) continue
                val key = pack(floor(data[i] / cellSize).toInt(), floor(data[i + 1] / cellSize).toInt())
                counts[key] = (counts[key] ?: 0) + 1
            }

            val buckets = HashMap<Long, IntArray>(counts.size)
            for ((k, c) in counts) buckets[k] = IntArray(c)

            val cursors = HashMap<Long, Int>(counts.size)
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (!DicResult.isAcceptedPoint(corr)) continue
                val key = pack(floor(data[i] / cellSize).toInt(), floor(data[i + 1] / cellSize).toInt())
                val pos = cursors[key] ?: 0
                buckets.getValue(key)[pos] = i
                cursors[key] = pos + 1
            }
            return PointSpatialIndex(data, cellSize, buckets)
        }

        private fun pack(cx: Int, cy: Int): Long =
            (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    }
}
