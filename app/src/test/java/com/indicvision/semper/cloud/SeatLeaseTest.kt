package com.indicvision.semper.cloud

import com.indicvision.semper.data.account.SeatLease
import com.indicvision.semper.data.net.AppConfigDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Floating-seat release and renew gates ([SeatLease.seatCall], which both
 * share). The network is faked so the order of decisions — whether to call,
 * and whether a failure is swallowed — is what these tests pin.
 */
class SeatLeaseTest {

    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val steps = mutableListOf<String>()

    private fun config(): AppConfigDto = AppConfigDto(mode = "demo")

    /** The release as `releaseBestEffort` makes it, with the config apply recorded in [steps]. */
    private suspend fun release(holdsSeat: Boolean = true): Boolean =
        SeatLease.seatCall(holdsSeat, api, tokens, "release") {
            releaseLease(it)
            steps.add("apply")
        }

    @Test
    fun `sign-out release runs only while a floating seat is held`() = runBlocking {
        api.onReleaseLease = {
            steps.add("release")
            config()
        }

        assertTrue(release())
        assertEquals(listOf("release", "apply"), steps)
        assertEquals(listOf("releaseLease"), api.calls)
    }

    @Test
    fun `sign-out without a held seat never hits the API`() = runBlocking {
        assertFalse(release(holdsSeat = false))
        assertTrue(steps.isEmpty())
        assertTrue(api.calls.isEmpty())
        assertEquals("no token is asked for", 0, tokens.asked)
    }

    @Test
    fun `a failed release still finishes sign-out rather than throwing`() = runBlocking {
        api.onReleaseLease = { error("network") }

        assertFalse(release())
        assertFalse(steps.contains("apply"))
    }

    @Test
    fun `a Firebase-style cancelled task during the release is a failure, not a cancelled sign-out`() = runBlocking {
        // suspendRunCatching rethrew this, which aborted signOut before the
        // session was cleared; the caller here is still active.
        api.onReleaseLease = { throw CancellationException("Task was cancelled") }

        assertFalse(release())
        assertFalse(steps.contains("apply"))
    }

    @Test
    fun `no backend or no token means no call`() = runBlocking {
        api.enabled = false
        assertFalse(release())
        api.enabled = true
        tokens.token = null
        assertFalse(release())

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `heartbeat renews with the checkout route, not a separate path`() = runBlocking {
        api.onCheckoutLease = {
            steps.add("checkout")
            config()
        }

        val ok = SeatLease.seatCall(holdsSeat = true, api, tokens, "heartbeat") {
            checkoutLease(it)
            steps.add("apply")
        }

        assertTrue(ok)
        assertEquals(listOf("checkout", "apply"), steps)
        assertEquals(listOf("checkoutLease"), api.calls)
    }

    @Test
    fun `heartbeat is skipped when the seat is already gone`() = runBlocking {
        val ok = SeatLease.seatCall(holdsSeat = false, api, tokens, "heartbeat") {
            checkoutLease(it)
            steps.add("apply")
        }

        assertFalse(ok)
        assertTrue(steps.isEmpty())
        assertTrue(api.calls.isEmpty())
    }
}
