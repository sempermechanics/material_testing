package com.rafad.indicvisiondic

import androidx.lifecycle.ViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AnalysisViewModel : ViewModel() {

    // ✅ NATIVE THREAD PINNING: A single persistent OS thread for ALL JNI/OpenMP calls.
    // The LLVM OpenMP runtime registers its master thread via TLS on the first call to
    // #pragma omp parallel. If subsequent calls come from a DIFFERENT OS thread, OpenMP's
    // TLS lookup returns null → SIGSEGV inside __kmp_invoke_microtask.
    // Solution: always run native calls on this one thread — it never changes.
    val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "IndicVision-NativeThread").also { it.isDaemon = true }
    }

    override fun onCleared() {
        super.onCleared()
        nativeExecutor.shutdown()
    }

    // ✅ Reference Image (loaded once, stays in memory)
    var refBytes: ByteArray? = null
    var roiMaskBytes: ByteArray? = null

    // 🚀 NEW: Store deformed image FILE PATHS instead of holding all bytes in RAM
    // This is critical to prevent OutOfMemory (OOM) crashes during batch processing
    var defFilePaths: List<String> = emptyList()

    // Store Image Info
    var realRefWidth: Int = 0
    var realRefHeight: Int = 0
    var refName: String = "No image selected"
    // Note: defName is now dynamically handled by getDefDisplayName()

    // 🚀 NEW: Batch metadata properties
    val defCount: Int
        get() = defFilePaths.size

    val batchMode: Boolean
        get() = defFilePaths.size > 1

    // Store ROI Info
    var hasCustomRoi: Boolean = false
    var roiX: Int = 0
    var roiY: Int = 0
    var roiW: Int = 0
    var roiH: Int = 0

    // Store Last Results State
    var lastStep: Int = 5
    var lastBatchDirPath: String? = null
    var lastRefPath: String? = null       // 🚀 ADDED: Tracks the dynamic Reference Image path
    var lastDefPath: String? = null
    var hasCompletedAnalysis: Boolean = false

    var currentSessionId: String? = null
    var engineStatsArray: FloatArray? = null

    fun isReadyToCompute(): Boolean {
        return refBytes != null && defFilePaths.isNotEmpty()
    }

    fun getDefDisplayName(): String {
        return when {
            defFilePaths.isEmpty() -> "No images selected"
            defFilePaths.size == 1 -> "Def: ${defFilePaths[0].substringAfterLast('/')}"
            else -> "${defFilePaths.size} images selected"
        }
    }

    // 🚀 UPDATED: Clear stale result metadata when a new selection is made.
    fun clearPreviousResults() {
        lastBatchDirPath = null
        lastRefPath = null // 🚀 Clears the old ghost reference
        lastDefPath = null
        hasCompletedAnalysis = false
    }
}