package com.indicvision.semper.ui.common

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.indicvision.semper.R

/**
 * Asks *where* to pick media from, in a sheet styled like the rest of the app.
 *
 * The system pickers run in their own window: they can't be given a title, and
 * anything we draw over them is either dimmed behind their scrim (a Snackbar) or
 * covers their own chrome (a Toast). So the instruction and the source choice
 * live here — in a window we control — and only then do we hand off to the
 * picker the user asked for.
 *
 * [onPhotos] should launch the system Photo Picker (gallery / Google Photos);
 * [onFiles] the Storage Access Framework (Downloads, Drive, on-device storage),
 * which is also the route that reaches formats the Photo Picker won't index,
 * such as DNG.
 */
object MediaSourceChooser {

    fun show(
        activity: Activity,
        @StringRes titleRes: Int,
        @StringRes captionRes: Int,
        onPhotos: () -> Unit,
        onFiles: () -> Unit,
    ) {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_media_source, null)
        view.findViewById<TextView>(R.id.tvSourceTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.tvSourceCaption).setText(captionRes)
        view.findViewById<View>(R.id.rowSourcePhotos).setOnClickListener {
            sheet.dismiss()
            onPhotos()
        }
        view.findViewById<View>(R.id.rowSourceFiles).setOnClickListener {
            sheet.dismiss()
            onFiles()
        }
        sheet.setContentView(view)
        sheet.show()
    }
}
