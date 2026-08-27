package com.indicvision.semper.ui.capture

import android.app.ActivityManager
import android.content.Context

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

    fun availStorageBytes(context: Context): Long = context.cacheDir.usableSpace
}
