package com.sempermechanics.semper.data.account.entitlement

/**
 * One reading of the device's clocks: epoch millis and boot-relative millis,
 * nothing else. No time zone, calendar or DST rule enters anything here, so
 * changing any of them changes no verdict.
 */
data class DeviceTime(
    /** `System.currentTimeMillis()`: the user can set it. */
    val wallMs: Long,
    /** `SystemClock.elapsedRealtime()`: monotonic, counts deep sleep, restarts at 0 on boot. */
    val elapsedMs: Long,
    /** `Settings.Global.BOOT_COUNT`, or [UNKNOWN_BOOT] when the platform does not say. */
    val bootCount: Int,
) {
    companion object {
        const val UNKNOWN_BOOT = -1
    }
}

/**
 * What [EntitlementClock] keeps between launches, sealed with the token
 * ([SealedEntitlement]).
 */
data class ClockState(
    /** The latest server time learned (token `iat`, or the API's `Date`), epoch ms. */
    val lastServerMs: Long,
    /** The device's wall clock when [lastServerMs] was learned. */
    val wallAtServerMs: Long,
    /** The latest wall clock seen since [lastServerMs]: the rollback high-water mark. */
    val highWaterWallMs: Long,
    /** Uptime accumulated since [lastServerMs], across reboots: a floor on real time passed. */
    val elapsedSinceServerMs: Long,
    /** `elapsedRealtime` at the last reading, to accumulate the next delta from. */
    val lastElapsedMs: Long,
    /** The boot the last reading was taken in. */
    val lastBootCount: Int,
    /** Rollback seen; stays set until the next server time. */
    val rolledBack: Boolean,
)

/** Why offline use has ended. */
enum class GraceEnd {
    /** The wall clock went back more than [EntitlementClock.ROLLBACK_TOLERANCE_MS]. */
    ROLLBACK,

    /** The stored state was altered or could not be checked. */
    TAMPERED,

    /** Past the token's `graceUntil`: time to check in. */
    GRACE_OVER,

    /** Past the token's `exp`. */
    EXPIRED,
}

/** Whether a verified token still entitles this device offline. */
sealed interface GraceVerdict {
    data object Within : GraceVerdict

    data class Ended(val why: GraceEnd) : GraceVerdict
}

/**
 * Offline grace and clock-rollback rules for an entitlement token.
 *
 * - **Effective now** is the later of two estimates of the server's clock:
 *   the last server time plus how far the wall clock has moved since, and the
 *   last server time plus the uptime accumulated since. The first follows the
 *   wall clock's *movement*, not its value, so a phone whose clock is simply
 *   wrong (the case `ClientNonce` handles for nonces) is neither punished nor
 *   rewarded; on a phone with a correct clock it is the wall clock. The second
 *   cannot be wound back: `elapsedRealtime` only moves forward.
 * - **Rollback**: a wall clock more than [ROLLBACK_TOLERANCE_MS] behind the
 *   highest one seen (which is never below the reading at the last server
 *   time) ends grace until the next server time. That covers setting the
 *   clock back, and setting it back past the last check-in.
 * - **Reboot**: `elapsedRealtime` restarts, so the time since boot is added,
 *   which undercounts the time the phone was off. Across a reboot the wall
 *   clock and its high-water mark are what remain.
 *
 * Grace ending never locks anything: the caller falls back to demo
 * entitlements and keeps every local session readable. Not tamper-proof on a
 * rooted phone; its job is to make casual clock changes useless, while `exp`
 * and revocation at the next check-in are the server's controls.
 */
object EntitlementClock {

    const val ROLLBACK_TOLERANCE_MS = 10L * 60 * 1000
    private const val MS_PER_SECOND = 1000L

    /** A fresh state anchored at [serverMs], read [now]. */
    fun anchored(serverMs: Long, now: DeviceTime): ClockState = ClockState(
        lastServerMs = serverMs,
        wallAtServerMs = now.wallMs,
        highWaterWallMs = now.wallMs,
        elapsedSinceServerMs = 0L,
        lastElapsedMs = now.elapsedMs,
        lastBootCount = now.bootCount,
        rolledBack = false,
    )

    /**
     * A server time learned [now]. One at or past the anchor re-anchors (and
     * clears a rollback: the server has said what time it is); an older one
     * cannot wind the clock back and is only a reading. The new anchor is never
     * below the uptime floor the old one had reached.
     */
    fun observeServer(state: ClockState?, serverMs: Long, now: DeviceTime): ClockState {
        val advanced = state?.let { advance(it, now) }
        return when {
            advanced == null -> anchored(serverMs, now)
            serverMs < advanced.lastServerMs -> advanced
            else -> anchored(maxOf(serverMs, advanced.lastServerMs + advanced.elapsedSinceServerMs), now)
        }
    }

    /** [state] brought forward to [now]: uptime accumulated, high-water raised, rollback latched. */
    fun advance(state: ClockState, now: DeviceTime): ClockState {
        val sameBoot = now.elapsedMs >= state.lastElapsedMs &&
            (now.bootCount == state.lastBootCount || now.bootCount == DeviceTime.UNKNOWN_BOOT)
        val delta = if (sameBoot) now.elapsedMs - state.lastElapsedMs else now.elapsedMs.coerceAtLeast(0L)
        val rolledBack = state.rolledBack || now.wallMs < state.highWaterWallMs - ROLLBACK_TOLERANCE_MS
        return state.copy(
            elapsedSinceServerMs = state.elapsedSinceServerMs + delta,
            lastElapsedMs = now.elapsedMs,
            lastBootCount = now.bootCount,
            highWaterWallMs = maxOf(state.highWaterWallMs, now.wallMs),
            rolledBack = rolledBack,
        )
    }

    /** The server's clock as best this device can tell, for a state already [advance]d to [now]. */
    fun effectiveNowMs(state: ClockState, now: DeviceTime): Long = maxOf(
        state.lastServerMs + (now.wallMs - state.wallAtServerMs),
        state.lastServerMs + state.elapsedSinceServerMs,
    )

    /** The state to keep, advanced to [now], and whether grace holds. Token times are epoch seconds. */
    fun evaluate(
        state: ClockState,
        now: DeviceTime,
        graceUntilSeconds: Long,
        expSeconds: Long,
    ): Pair<ClockState, GraceVerdict> {
        val advanced = advance(state, now)
        val effective = effectiveNowMs(advanced, now)
        val verdict = when {
            advanced.rolledBack -> GraceVerdict.Ended(GraceEnd.ROLLBACK)
            effective >= expSeconds * MS_PER_SECOND -> GraceVerdict.Ended(GraceEnd.EXPIRED)
            effective > graceUntilSeconds * MS_PER_SECOND -> GraceVerdict.Ended(GraceEnd.GRACE_OVER)
            else -> GraceVerdict.Within
        }
        return advanced to verdict
    }
}
