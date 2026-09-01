package com.indicvision.semper.ui.capture

/**
 * Resolves the lens distance [LockedCameraSession.lockFocusAndExposure]
 * should lock at, or `null` when AF failed to lock and the caller must
 * refuse to start the sequence. Kept separate (and pure) so "AF lock-failed
 * refuses to start" is testable without a real Camera2 session.
 */
internal object AfLockResolver {

    /**
     * @param dist distance reported when AF reached a locked/focused state,
     *   or null on timeout
     * @param lastDist most recent distance sampled regardless of AF state
     * @param allowFallback true only on an emulator/debug build, where some
     *   HAL stacks never report a locked state at all
     */
    fun resolve(dist: Float?, lastDist: Float?, allowFallback: Boolean): Float? =
        when {
            dist != null -> dist
            allowFallback -> lastDist ?: 0f
            else -> null
        }
}
