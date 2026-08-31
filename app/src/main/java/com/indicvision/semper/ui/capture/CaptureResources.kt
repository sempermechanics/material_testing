package com.indicvision.semper.ui.capture

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager

/**
 * Free RAM and storage for the capture budget gate, shared by the setup and
 * session screens so both gate against the same numbers.
 */
internal object CaptureResources {

    fun availRamBytes(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    fun availStorageBytes(context: Context): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = context.getSystemService(StorageManager::class.java)
            if (sm != null) {
                return runCatching {
                    sm.getAllocatableBytes(sm.getUuidForPath(context.cacheDir))
                }.getOrNull() ?: context.cacheDir.usableSpace
            }
        }
        return context.cacheDir.usableSpace
    }
}
