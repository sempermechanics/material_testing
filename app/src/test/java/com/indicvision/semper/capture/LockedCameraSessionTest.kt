package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.AfLockResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AfLockResolver] is the pure decision at the heart of
 * `LockedCameraSession.lockFocusAndExposure()` — kept separate from the real
 * Camera2 session so "AF lock-failed refuses to start the sequence" is
 * testable without standing up a CameraDevice/CaptureSession.
 */
class LockedCameraSessionTest {

    @Test
    fun `AF locked returns the reported distance`() {
        assertEquals(1.5f, AfLockResolver.resolve(dist = 1.5f, lastDist = 0.8f, allowFallback = false))
    }

    @Test
    fun `AF timeout on a real device refuses to start the sequence`() {
        assertNull(AfLockResolver.resolve(dist = null, lastDist = 0.8f, allowFallback = false))
    }

    @Test
    fun `AF timeout with no prior sample and no fallback also refuses`() {
        assertNull(AfLockResolver.resolve(dist = null, lastDist = null, allowFallback = false))
    }

    @Test
    fun `AF timeout on an emulator debug build falls back to the last observed distance`() {
        assertEquals(0.8f, AfLockResolver.resolve(dist = null, lastDist = 0.8f, allowFallback = true))
    }

    @Test
    fun `AF timeout on an emulator debug build with no prior sample falls back to zero`() {
        assertEquals(0f, AfLockResolver.resolve(dist = null, lastDist = null, allowFallback = true))
    }
}
