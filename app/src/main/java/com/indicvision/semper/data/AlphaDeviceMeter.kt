@file:Suppress("MagicNumber")

package com.indicvision.semper.data

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Alpha-matrix device footprint: RAM (PSS / Java / native) and ROM (app package
 * + sessions + cache). Samples append to an external-files CSV the laptop can
 * `adb pull` without root, and also print to logcat under [LOG_TAG] so
 * `adb logcat -s AlphaMeter:I` works on release betas (bypasses Timber's
 * WARN-only release tree).
 */
object AlphaDeviceMeter {

    const val LOG_TAG = "AlphaMeter"
    const val DIR_NAME = "alpha_meter"
    const val CSV_NAME = "samples.csv"
    private const val BYTES_PER_KB = 1024L

    /** Serial IO so UI / worker callers never block on StorageStats or CSV. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlphaDeviceMeter").apply { isDaemon = true }
    }

    data class Sample(
        val utcIso: String,
        val label: String,
        val pssKb: Long,
        val javaUsedKb: Long,
        val javaMaxKb: Long,
        val nativeHeapKb: Long,
        val availMemKb: Long,
        val totalMemKb: Long,
        val lowMemory: Boolean,
        val appBytes: Long,
        val dataBytes: Long,
        val cacheBytes: Long,
        val sessionsBytes: Long,
        val cacheDirBytes: Long,
    ) {
        /** App-visible ROM: code + private data + cache (StorageStats). */
        val romBytes: Long get() = appBytes + dataBytes + cacheBytes

        fun toCsvLine(): String = listOf(
            utcIso,
            label,
            pssKb,
            javaUsedKb,
            javaMaxKb,
            nativeHeapKb,
            availMemKb,
            totalMemKb,
            if (lowMemory) 1 else 0,
            appBytes,
            dataBytes,
            cacheBytes,
            sessionsBytes,
            cacheDirBytes,
            romBytes,
        ).joinToString(",")

        fun toLogLine(): String = String.format(
            Locale.US,
            "label=%s pss_kb=%d java_kb=%d/%d native_kb=%d avail_kb=%d rom_bytes=%d " +
                "(app=%d data=%d cache=%d) sessions_bytes=%d cache_dir_bytes=%d low=%s",
            label,
            pssKb,
            javaUsedKb,
            javaMaxKb,
            nativeHeapKb,
            availMemKb,
            romBytes,
            appBytes,
            dataBytes,
            cacheBytes,
            sessionsBytes,
            cacheDirBytes,
            lowMemory,
        )
    }

    const val CSV_HEADER =
        "utc_iso,label,pss_kb,java_used_kb,java_max_kb,native_heap_kb," +
            "avail_mem_kb,total_mem_kb,low_memory,app_bytes,data_bytes,cache_bytes," +
            "sessions_bytes,cache_dir_bytes,rom_bytes"

    /**
     * Capture one sample. Safe to call from any thread; does blocking I/O for
     * sizes — prefer [Dispatchers.IO] from UI.
     */
    fun sample(context: Context, label: String): Sample {
        val app = context.applicationContext
        val rt = Runtime.getRuntime()
        val javaUsed = ((rt.totalMemory() - rt.freeMemory()) / BYTES_PER_KB).coerceAtLeast(0L)
        val javaMax = (rt.maxMemory() / BYTES_PER_KB).coerceAtLeast(0L)
        val nativeHeap = (Debug.getNativeHeapAllocatedSize() / BYTES_PER_KB).coerceAtLeast(0L)

        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        val pssKb = memInfo.totalPss.toLong()

        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo()
        am.getMemoryInfo(sys)
        val availKb = sys.availMem / BYTES_PER_KB
        val totalKb = sys.totalMem / BYTES_PER_KB

        val (appB, dataB, cacheB) = packageStorageBytes(app)
        val sessionsB = runCatching { SessionStore.totalSize(app) }.getOrDefault(0L)
        val cacheDirB = runCatching { CacheJanitor.sizeOf(app.cacheDir) }.getOrDefault(0L)

        return Sample(
            utcIso = Instant.now().toString(),
            label = label.replace(',', '_').replace('\n', '_'),
            pssKb = pssKb,
            javaUsedKb = javaUsed,
            javaMaxKb = javaMax,
            nativeHeapKb = nativeHeap,
            availMemKb = availKb,
            totalMemKb = totalKb,
            lowMemory = sys.lowMemory,
            appBytes = appB,
            dataBytes = dataB,
            cacheBytes = cacheB,
            sessionsBytes = sessionsB,
            cacheDirBytes = cacheDirB,
        )
    }

    /** Sample, append CSV, and emit logcat. Never throws; IO is async. */
    fun record(context: Context, label: String) {
        val app = context.applicationContext
        io.execute {
            runCatching {
                val snap = sample(app, label)
                appendCsv(app, snap)
                // Direct Log so release betas still show samples in `adb logcat`.
                Log.i(LOG_TAG, snap.toLogLine())
            }.onFailure {
                Timber.w(it, "AlphaDeviceMeter.record(%s) failed", label)
            }
        }
    }

    fun csvFile(context: Context): File {
        val dir = context.applicationContext.getExternalFilesDir(DIR_NAME)
            ?: File(context.applicationContext.filesDir, DIR_NAME).also { it.mkdirs() }
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CSV_NAME)
    }

    private fun appendCsv(context: Context, sample: Sample) {
        val file = csvFile(context)
        if (!file.exists() || file.length() == 0L) {
            file.writeText(CSV_HEADER + "\n")
        }
        file.appendText(sample.toCsvLine() + "\n")
    }

    private fun packageStorageBytes(context: Context): Triple<Long, Long, Long> =
        runCatching {
            val ssm = context.getSystemService(StorageStatsManager::class.java) ?: return@runCatching Triple(0L, 0L, 0L)
            val uuid = context.applicationInfo.storageUuid
                ?: StorageManager.UUID_DEFAULT
            val stats = ssm.queryStatsForPackage(uuid, context.packageName, Process.myUserHandle())
            Triple(stats.appBytes, stats.dataBytes, stats.cacheBytes)
        }.getOrElse { Triple(0L, 0L, 0L) }
}
