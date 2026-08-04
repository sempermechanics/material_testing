package com.indicvision.semper.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.indicvision.semper.R

/** Foreground notification for expedited upload / restore workers. */
object TransferNotifications {

    private const val CHANNEL_ID = "semper_transfers"
    private const val UPLOAD_NOTIF_ID = 4101
    private const val RESTORE_NOTIF_ID = 4102

    fun uploadForeground(context: Context): ForegroundInfo =
        foregroundInfo(context, UPLOAD_NOTIF_ID, R.string.transfer_upload_title)

    fun restoreForeground(context: Context): ForegroundInfo =
        foregroundInfo(context, RESTORE_NOTIF_ID, R.string.transfer_restore_title)

    private fun foregroundInfo(context: Context, id: Int, titleRes: Int): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(titleRes))
            .setSmallIcon(R.drawable.ic_cloud_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(id, notification)
    }

    @Suppress("ReturnCount") // SDK / missing service / already-created early outs
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
