package com.indicvision.semper.ui.common

import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R

/** Three equal-weight actions on one row. Labels and callbacks stay with the caller. */
object DeleteChoiceDialog {

    fun show(
        activity: AppCompatActivity,
        title: CharSequence,
        message: CharSequence,
        leftLabel: CharSequence,
        midLabel: CharSequence,
        rightLabel: CharSequence,
        onLeft: () -> Unit,
        onMid: () -> Unit,
        onRight: (() -> Unit)? = null,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_delete_choice, null)
        view.findViewById<TextView>(R.id.tvDeleteMessage).text = message
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(view)
            .create()
        view.findViewById<MaterialButton>(R.id.btnDeleteLeft).apply {
            text = leftLabel
            setOnClickListener {
                dialog.dismiss()
                onLeft()
            }
        }
        view.findViewById<MaterialButton>(R.id.btnDeleteMid).apply {
            text = midLabel
            setOnClickListener {
                dialog.dismiss()
                onMid()
            }
        }
        view.findViewById<MaterialButton>(R.id.btnDeleteRight).apply {
            text = rightLabel
            setOnClickListener {
                dialog.dismiss()
                onRight?.invoke()
            }
        }
        dialog.show()
    }
}
