package com.indicvision.semper.ui.capture

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

/**
 * The resolution spinner and the list behind it.
 *
 * The catalogue is [CameraCapabilities.Info.yuvSizes], not the vendor JPEG
 * sizes: the locked session captures YUV_420_888 (see [LockedCameraSession])
 * and encodes that to lossless PNG, so a JPEG-only size would name a
 * resolution the still pipeline cannot actually produce. That list is
 * already 4:3-only and bounded by [CameraCapabilities.sustainableCeiling].
 *
 * Resolution feeds the rate: a larger frame costs more to read out and more to
 * encode, so [onChanged] has to rebuild the offered rates, not just relabel.
 */
internal class CaptureResolutionPicker(
    private val spinner: Spinner,
    sizes: List<CameraCapabilities.Resolution>,
    private val onChanged: () -> Unit,
) {

    private val sizes = sizes.ifEmpty { listOf(CameraCapabilities.LAST_RESORT) }

    init {
        spinner.adapter = ArrayAdapter(
            spinner.context,
            android.R.layout.simple_spinner_dropdown_item,
            this.sizes.map { it.label },
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                onChanged()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    val selected: CameraCapabilities.Resolution
        get() = sizes.getOrElse(spinner.selectedItemPosition) { sizes.first() }
}
