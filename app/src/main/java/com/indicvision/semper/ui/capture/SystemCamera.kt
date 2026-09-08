package com.indicvision.semper.ui.capture

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Builds capture-and-return intents aimed at the manufacturer Camera app.
 */
object SystemCamera {

    fun captureDir(context: Context): File =
        File(context.cacheDir, "capture").also { it.mkdirs() }

    fun fileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun stillCaptureIntent(context: Context, outputUri: Uri): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "capture", outputUri)
        }
        return bindDefaultHandler(context, intent)
    }

    fun videoCaptureIntent(
        context: Context,
        outputUri: Uri,
        durationLimitSec: Int,
    ): Intent? {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, durationLimitSec.coerceAtLeast(1))
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "capture", outputUri)
        }
        return bindDefaultHandler(context, intent)
    }

    fun canCaptureStill(context: Context): Boolean =
        resolveHandler(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE)) != null

    fun canCaptureVideo(context: Context): Boolean =
        resolveHandler(context, Intent(MediaStore.ACTION_VIDEO_CAPTURE)) != null

    private fun bindDefaultHandler(context: Context, intent: Intent): Intent? {
        val info = resolveHandler(context, intent) ?: return null
        intent.setPackage(info.activityInfo.packageName)
        intent.setClassName(info.activityInfo.packageName, info.activityInfo.name)
        return intent
    }

    private fun resolveHandler(context: Context, intent: Intent) =
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
}
