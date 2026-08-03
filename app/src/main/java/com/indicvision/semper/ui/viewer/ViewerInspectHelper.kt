// Inspect gestures: literal touch thresholds and the combined move/tap
// conditions read clearest inline, so MagicNumber / ComplexCondition are
// suppressed for this whole file.
@file:Suppress("MagicNumber", "ComplexCondition")

@file:SuppressLint("ClickableViewAccessibility", "SetTextI18n")

package com.indicvision.semper.ui.viewer

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import android.widget.ToggleButton
import androidx.cardview.widget.CardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.report.ReportBuilder

/**
 * Point inspection, max/min markers, glass shield, and the coordinate dialog.
 * Frame data and field index stay on [ResultViewerActivity]; this owns the
 * overlay UI and the last-picked indices.
 */
class ViewerInspectHelper(private val host: ResultViewerActivity) {

    private val imgMain: TouchImageView get() = host.imgMain
    private val glassShield: InspectOverlayView get() = host.glassShield
    private val cardInspectorHud: CardView get() = host.cardInspectorHud
    private val tvInspectorData get() = host.tvInspectorData
    private val cardMaxMinHud: CardView get() = host.cardMaxMinHud
    private val tvMaxMinData get() = host.tvMaxMinData
    private val toggleInspect: ToggleButton get() = host.toggleInspect

    var isInspectModeActive = false
    var isMaxMinActive = false
    var lastClosestIdx = -1
    var lastMaxIdx = -1
    var lastMinIdx = -1

    /** Spatial buckets for the current frame's accepted points; rebuilt on frame load. */
    private var spatialIndex: PointSpatialIndex? = null

    fun rebuildSpatialIndex(data: FloatArray, step: Int) {
        spatialIndex = PointSpatialIndex.build(data, step)
    }

    fun clearSpatialIndex() {
        spatialIndex = null
    }

    fun wireGlassShieldTouch() {
        glassShield.setOnTouchListener { view, event ->
            // The shield forwards taps rather than handling clicks itself, but
            // accessibility services still need the click hook to fire.
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            if (!isInspectModeActive) {
                imgMain.dispatchTouchEvent(event)
                return@setOnTouchListener true
            }

            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val pts = floatArrayOf(event.x, event.y)
                val inverse = android.graphics.Matrix()
                // Logical matrix (true imgW/imgH), not the draw matrix that
                // may include display-bitmap content scale.
                imgMain.getZoomMatrix().invert(inverse)
                inverse.mapPoints(pts)
                findNearestDataPoint(pts[0], pts[1])
            }
            true
        }
    }

    fun manageGlassShieldState() {
        if (isInspectModeActive || isMaxMinActive) {
            glassShield.visibility = View.VISIBLE
        } else {
            glassShield.visibility = View.GONE
        }
    }

    fun calculateMaxMin() {
        val (maxIdx, minIdx) = computeMaxMinIndices()
        lastMaxIdx = maxIdx
        lastMinIdx = minIdx
    }

    fun computeMaxMinIndices(): Pair<Int, Int> {
        val data = host.rawData ?: return -1 to -1
        val extrema = ReportBuilder.computeFieldExtrema(
            data,
            host.currentDataIndex,
            absoluteStrainValues = false,
        )
        return extrema.maxIdx to extrema.minIdx
    }

    fun findNearestDataPoint(physX: Float, physY: Float) {
        val data = host.rawData ?: return
        val searchRadius = host.step * 1.5f
        val index = spatialIndex ?: PointSpatialIndex.build(data, host.step).also { spatialIndex = it }
        lastClosestIdx = index.nearest(physX, physY, searchRadius)
        refreshCrosshairs()
    }

    fun refreshCrosshairs() {
        val data = host.rawData ?: return

        val dataIndex = host.currentDataIndex
        val typeString = host.currentTypeString
        val isStrain = DicResult.isStrainFieldIndex(dataIndex)
        val multiplier = DicResult.strainMultiplier(dataIndex)
        val unit = if (isStrain) "mε" else "px"

        if (isInspectModeActive) {
            cardInspectorHud.visibility = View.VISIBLE

            if (lastClosestIdx != -1 && lastClosestIdx < data.size) {
                val actualX = data[lastClosestIdx].toInt()
                val actualY = data[lastClosestIdx + 1].toInt()
                val value = data[lastClosestIdx + dataIndex] * multiplier

                tvInspectorData.text =
                    "Loc: ($actualX, $actualY)\n$typeString: ${ReportBuilder.formatMetric(value)} $unit"

                val pts = floatArrayOf(actualX.toFloat(), actualY.toFloat())
                imgMain.getZoomMatrix().mapPoints(pts)
                glassShield.updatePosition(pts[0], pts[1])
            } else {
                glassShield.hide()
                tvInspectorData.text = "Out of bounds / No Data"
            }
        } else {
            glassShield.hide()
            cardInspectorHud.visibility = View.GONE
        }

        if (isMaxMinActive && lastMaxIdx != -1 && lastMinIdx != -1) {
            val maxX = data[lastMaxIdx].toInt()
            val maxY = data[lastMaxIdx + 1].toInt()
            val minX = data[lastMinIdx].toInt()
            val minY = data[lastMinIdx + 1].toInt()

            val maxV = data[lastMaxIdx + dataIndex] * multiplier
            val minV = data[lastMinIdx + dataIndex] * multiplier

            val ptsMax = floatArrayOf(maxX.toFloat(), maxY.toFloat())
            val ptsMin = floatArrayOf(minX.toFloat(), minY.toFloat())
            imgMain.getZoomMatrix().mapPoints(ptsMax)
            imgMain.getZoomMatrix().mapPoints(ptsMin)

            glassShield.updateMaxMinPositions(ptsMax[0], ptsMax[1], ptsMin[0], ptsMin[1])

            tvMaxMinData.text =
                "🔴 MAX: ($maxX, $maxY) = ${ReportBuilder.formatMetric(maxV)} $unit\n" +
                "🔵 MIN: ($minX, $minY) = ${ReportBuilder.formatMetric(minV)} $unit"
            cardMaxMinHud.visibility = View.VISIBLE
        } else {
            glassShield.hideMaxMin()
            cardMaxMinHud.visibility = View.GONE
        }
    }

    fun showCoordinateInputDialog() {
        val dialogView = host.layoutInflater.inflate(R.layout.dialog_coordinate_input, null)
        val etX = dialogView.findViewById<TextInputEditText>(R.id.etCoordX)
        val etY = dialogView.findViewById<TextInputEditText>(R.id.etCoordY)
        dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCoordX).hint =
            host.getString(R.string.coord_hint_x, host.imgW)
        dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCoordY).hint =
            host.getString(R.string.coord_hint_y, host.imgH)

        MaterialAlertDialogBuilder(host)
            .setTitle(R.string.coord_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.find) { _, _ ->
                val x = etX.text?.toString()?.toFloatOrNull()
                val y = etY.text?.toString()?.toFloatOrNull()

                if (x != null && y != null) {
                    if (x < 0 || x > host.imgW || y < 0 || y > host.imgH) {
                        Toast.makeText(host, R.string.coord_out_of_bounds, Toast.LENGTH_LONG).show()
                    } else {
                        if (!isInspectModeActive) toggleInspect.isChecked = true
                        findNearestDataPoint(x, y)
                    }
                } else {
                    Toast.makeText(host, R.string.invalid_input, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
