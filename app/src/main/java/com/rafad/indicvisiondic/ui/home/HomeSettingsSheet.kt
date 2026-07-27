// Settings sheet builds all its rows in one show(); kept together for locality.
@file:Suppress("LongMethod")

package com.rafad.indicvisiondic.ui.home

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.DicSettings
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.ui.admin.AdminActivity
import com.rafad.indicvisiondic.ui.restore.RestoreActivity

/**
 * Home gear drawer: cloud/local preferences plus account actions. Destructive
 * account work stays in the Activity via [Callbacks].
 */
object HomeSettingsSheet {

    interface Callbacks {
        fun onSignOut()
        fun onExportData()
        fun onDeleteAccount()
    }

    fun show(activity: AppCompatActivity, callbacks: Callbacks) {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_home_settings, null)
        sheet.setContentView(view)

        view.findViewById<SwitchMaterial>(R.id.switchSaveCloud).apply {
            isChecked = DicSettings.saveToCloud(activity)
            setOnCheckedChangeListener { _, v -> DicSettings.setSaveToCloud(activity, v) }
        }
        view.findViewById<SwitchMaterial>(R.id.switchKeepRerun).apply {
            isChecked = DicSettings.keepEveryRerun(activity)
            setOnCheckedChangeListener { _, v -> DicSettings.setKeepEveryRerun(activity, v) }
        }

        val valueLabel = view.findViewById<TextView>(R.id.tvMaxFramesValue)
        view.findViewById<Slider>(R.id.sliderMaxFrames).apply {
            value = DicSettings.maxFrames(activity).toFloat()
            valueLabel.text = value.toInt().toString()
            addOnChangeListener { _, v, _ ->
                valueLabel.text = v.toInt().toString()
                DicSettings.setMaxFrames(activity, v.toInt())
            }
        }
        view.findViewById<ImageButton>(R.id.btnMaxFramesInfo).setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_max_frames)
                .setMessage(R.string.setting_max_frames_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        view.findViewById<TextView>(R.id.tvAccountEmail).text =
            TokenStore.cachedEmail(activity) ?: ""

        view.findViewById<View>(R.id.btnRestoreCloud).setOnClickListener {
            sheet.dismiss()
            activity.startActivity(Intent(activity, RestoreActivity::class.java))
        }

        // Admin entry: only for accounts whose backend role is admin.
        view.findViewById<View>(R.id.btnAdmin).apply {
            visibility = if (TokenStore.isAdmin(activity)) View.VISIBLE else View.GONE
            setOnClickListener {
                sheet.dismiss()
                activity.startActivity(Intent(activity, AdminActivity::class.java))
            }
        }

        view.findViewById<View>(R.id.btnAbout).setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.about_title)
                .setMessage("inDIC v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        view.findViewById<View>(R.id.btnSignOut).setOnClickListener {
            sheet.dismiss()
            callbacks.onSignOut()
        }
        view.findViewById<View>(R.id.btnExportData).setOnClickListener {
            sheet.dismiss()
            callbacks.onExportData()
        }
        view.findViewById<View>(R.id.btnDeleteAccount).setOnClickListener {
            sheet.dismiss()
            callbacks.onDeleteAccount()
        }

        sheet.show()
    }
}
