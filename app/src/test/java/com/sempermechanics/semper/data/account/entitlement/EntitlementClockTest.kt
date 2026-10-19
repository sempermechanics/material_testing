package com.sempermechanics.semper.data.account.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The offline matrix (framework Part E): airplane mode for an hour, for grace
 * − 1 day and grace + 1 day; the clock set forward then back; set back past
 * the last check-in; a time-zone change; a reboot mid-offline; expiry while
 * offline. The token was issued at [SERVER_MS] with 7 days of grace.
 */
class EntitlementClockTest {

    private val graceUntil = TestTokens.IAT + GRACE_DAYS * DAY_S
    private val exp = TestTokens.IAT + 30 * DAY_S

    /** A reading [sinceMs] after check-in, by default on an accurate clock in the same boot. */
    private fun at(
        sinceMs: Long,
        wallMs: Long = SERVER_MS + sinceMs,
        elapsedMs: Long = ELAPSED_AT_SYNC + sinceMs,
        boot: Int = BOOT,
    ) = DeviceTime(wallMs, elapsedMs, boot)

    private val synced = EntitlementClock.anchored(SERVER_MS, at(0))

    private fun verdict(
        now: DeviceTime,
        state: ClockState = synced,
        grace: Long = graceUntil,
        expiry: Long = exp,
    ): GraceVerdict = EntitlementClock.evaluate(state, now, grace, expiry).second

    private fun step(state: ClockState, now: DeviceTime): ClockState =
        EntitlementClock.evaluate(state, now, graceUntil, exp).first

    private val within = GraceVerdict.Within
    private fun ended(why: GraceEnd) = GraceVerdict.Ended(why)

    @Test
    fun `offline for an hour is within grace`() {
        assertEquals(within, verdict(at(HOUR_MS)))
    }

    @Test
    fun `offline for grace minus a day is within, plus a day is over`() {
        assertEquals(within, verdict(at((GRACE_DAYS - 1) * DAY_MS)))
        assertEquals(ended(GraceEnd.GRACE_OVER), verdict(at((GRACE_DAYS + 1) * DAY_MS)))
    }

    @Test
    fun `grace holds to the second and ends just after`() {
        assertEquals(within, verdict(at(GRACE_DAYS * DAY_MS)))
        assertEquals(ended(GraceEnd.GRACE_OVER), verdict(at(GRACE_DAYS * DAY_MS + 1)))
    }

    @Test
    fun `the clock set forward and then back ends grace until the next check-in`() {
        val forward = step(synced, at(MINUTE_MS, wallMs = SERVER_MS + 2 * HOUR_MS))
        assertFalse(forward.rolledBack)
        val back = step(forward, at(2 * MINUTE_MS))
        assertEquals(ended(GraceEnd.ROLLBACK), verdict(at(2 * MINUTE_MS), back))
        // Latched: moving the clock forward again does not undo it.
        assertEquals(ended(GraceEnd.ROLLBACK), verdict(at(3 * MINUTE_MS, wallMs = SERVER_MS + 3 * HOUR_MS), back))
        // A server time re-anchors and clears it.
        val checkedIn = EntitlementClock.observeServer(back, SERVER_MS + 3 * MINUTE_MS, at(3 * MINUTE_MS))
        assertEquals(within, verdict(at(4 * MINUTE_MS), checkedIn))
    }

    @Test
    fun `the clock rolled back past the last check-in is rollback`() {
        assertEquals(ended(GraceEnd.ROLLBACK), verdict(at(HOUR_MS, wallMs = SERVER_MS - DAY_MS)))
    }

    @Test
    fun `a small correction back is tolerated`() {
        val ahead = step(synced, at(HOUR_MS))
        assertEquals(within, verdict(at(HOUR_MS + MINUTE_MS, wallMs = SERVER_MS + HOUR_MS - 9 * MINUTE_MS), ahead))
    }

    @Test
    fun `a time-zone or DST change changes nothing`() {
        val original = TimeZone.getDefault()
        try {
            val readings = listOf(at(HOUR_MS), at(6 * DAY_MS), at(8 * DAY_MS))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = readings.map { verdict(it) }
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"))
            val chatham = readings.map { verdict(it) }
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(utc, chatham)
            assertEquals(utc, readings.map { verdict(it) })
            assertEquals(listOf(within, within, ended(GraceEnd.GRACE_OVER)), utc)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `a reboot mid-offline keeps the uptime and the high-water mark`() {
        val beforeReboot = step(synced, at(3 * DAY_MS))
        val afterBoot = at(3 * DAY_MS + HOUR_MS, elapsedMs = 10 * MINUTE_MS, boot = BOOT + 1)
        val rebooted = step(beforeReboot, afterBoot)
        assertEquals(3 * DAY_MS + 10 * MINUTE_MS, rebooted.elapsedSinceServerMs)
        assertEquals(within, verdict(afterBoot, rebooted))
        // Setting the clock back after the reboot is still caught.
        assertEquals(
            ended(GraceEnd.ROLLBACK),
            verdict(at(DAY_MS, elapsedMs = 20 * MINUTE_MS, boot = BOOT + 1), rebooted),
        )
    }

    @Test
    fun `a reboot is detected by elapsed time when the boot count is unknown`() {
        val state = step(synced, at(DAY_MS, boot = DeviceTime.UNKNOWN_BOOT))
        val rebooted = step(state, at(DAY_MS + HOUR_MS, elapsedMs = MINUTE_MS, boot = DeviceTime.UNKNOWN_BOOT))
        assertEquals(DAY_MS + MINUTE_MS, rebooted.elapsedSinceServerMs)
    }

    @Test
    fun `uptime ends grace when the wall clock is held back`() {
        // The clock is reset to just after check-in, again and again, while the phone stays on.
        var state = synced
        for (day in 1..8) state = step(state, at(day * DAY_MS, wallMs = SERVER_MS + 5 * MINUTE_MS))
        assertFalse(state.rolledBack)
        assertEquals(
            ended(GraceEnd.GRACE_OVER),
            verdict(at(8 * DAY_MS, wallMs = SERVER_MS + 5 * MINUTE_MS), state),
        )
    }

    @Test
    fun `expiry while offline ends it even inside grace`() {
        val shortExp = TestTokens.IAT + 2 * DAY_S
        assertEquals(within, verdict(at(DAY_MS), expiry = shortExp))
        assertEquals(ended(GraceEnd.EXPIRED), verdict(at(2 * DAY_MS), expiry = shortExp))
        assertEquals(ended(GraceEnd.EXPIRED), verdict(at(3 * DAY_MS), expiry = shortExp))
    }

    @Test
    fun `a phone whose clock is simply wrong is neither punished nor rewarded`() {
        // Three hours slow at check-in, and still three hours slow after.
        val slow = EntitlementClock.anchored(SERVER_MS, at(0, wallMs = SERVER_MS - 3 * HOUR_MS))
        val wall = { since: Long -> SERVER_MS - 3 * HOUR_MS + since }
        assertEquals(within, verdict(at(HOUR_MS, wallMs = wall(HOUR_MS)), slow))
        assertEquals(within, verdict(at(GRACE_DAYS * DAY_MS, wallMs = wall(GRACE_DAYS * DAY_MS)), slow))
        assertEquals(ended(GraceEnd.GRACE_OVER), verdict(at(8 * DAY_MS, wallMs = wall(8 * DAY_MS)), slow))
        // Three hours fast does not end grace three hours early.
        val fast = EntitlementClock.anchored(SERVER_MS, at(0, wallMs = SERVER_MS + 3 * HOUR_MS))
        val fastWall = SERVER_MS + 3 * HOUR_MS + GRACE_DAYS * DAY_MS
        assertEquals(within, verdict(at(GRACE_DAYS * DAY_MS, wallMs = fastWall), fast))
    }

    @Test
    fun `an older server time does not wind the anchor back`() {
        val rolled = step(synced, at(HOUR_MS, wallMs = SERVER_MS - DAY_MS))
        val stale = EntitlementClock.observeServer(rolled, SERVER_MS - MINUTE_MS, at(2 * HOUR_MS))
        assertEquals(SERVER_MS, stale.lastServerMs)
        assertTrue(stale.rolledBack)
    }

    @Test
    fun `a re-anchor never lands below the uptime floor`() {
        val later = step(synced, at(2 * DAY_MS))
        val anchored = EntitlementClock.observeServer(later, SERVER_MS + HOUR_MS, at(2 * DAY_MS))
        assertEquals(SERVER_MS + 2 * DAY_MS, anchored.lastServerMs)
        assertEquals(0L, anchored.elapsedSinceServerMs)
    }

    @Test
    fun `the first server time anchors a fresh state`() {
        val first = EntitlementClock.observeServer(null, SERVER_MS, at(0))
        assertEquals(synced, first)
    }

    private companion object {
        const val GRACE_DAYS = TestTokens.GRACE_DAYS
        const val DAY_S = TestTokens.DAY_S
        const val SERVER_MS = TestTokens.IAT * 1000
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
        const val ELAPSED_AT_SYNC = 5 * HOUR_MS
        const val BOOT = 7
    }
}
