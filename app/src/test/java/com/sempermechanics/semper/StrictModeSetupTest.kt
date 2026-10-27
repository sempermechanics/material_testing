package com.sempermechanics.semper

import android.os.StrictMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * StrictMode in debug builds only (readiness TE1-006): disk and network work
 * on the main thread, and leaked closeables, are logged; nothing is ever
 * killed for it, and a release build sets nothing.
 */
@RunWith(RobolectricTestRunner::class)
class StrictModeSetupTest {

    @After
    fun reset() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    /** The policy's bit mask, as its toString prints it ("[StrictMode.ThreadPolicy; mask=…]"). */
    private fun mask(policy: Any): Int =
        checkNotNull(Regex("""mask=(-?\d+)""").find(policy.toString())) { policy.toString() }.groupValues[1].toInt()

    @Test
    fun `a debug build logs main-thread disk and network work and never dies for it`() {
        installStrictMode(debug = true)

        val thread = mask(StrictMode.getThreadPolicy())
        assertNotEquals("a thread policy is set", mask(StrictMode.ThreadPolicy.LAX), thread)
        assertEquals("penaltyLog", PENALTY_LOG, thread and PENALTY_LOG)
        assertEquals("no penaltyDeath", 0, thread and PENALTY_DEATH)
        assertNotEquals("a VM policy is set", mask(StrictMode.VmPolicy.LAX), mask(StrictMode.getVmPolicy()))
        assertEquals("no VM penaltyDeath", 0, mask(StrictMode.getVmPolicy()) and PENALTY_DEATH)
    }

    @Test
    fun `a release build sets no policy`() {
        // The test app's own onCreate ran as a debug build; start from none.
        reset()
        installStrictMode(debug = false)

        assertEquals(mask(StrictMode.ThreadPolicy.LAX), mask(StrictMode.getThreadPolicy()))
        assertEquals(mask(StrictMode.VmPolicy.LAX), mask(StrictMode.getVmPolicy()))
    }

    private companion object {
        /** StrictMode.PENALTY_LOG and PENALTY_DEATH (hidden; `1 << 30` and `1 << 28` in AOSP's StrictMode.java). */
        const val PENALTY_LOG = 1 shl 30
        const val PENALTY_DEATH = 1 shl 28
    }
}
