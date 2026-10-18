package com.sempermechanics.semper.data.account.entitlement

/** What the stored token means for the app right now. */
sealed interface TokenStatus {
    /**
     * No token in play: this build pins no keys, or the backend sends none.
     * The unsigned `/v1/config` cache alone decides, exactly as before tokens.
     */
    data object NotUsed : TokenStatus

    /** Verified, bound to this device, and inside its offline grace. */
    data class Valid(val claims: EntitlementClaims) : TokenStatus

    /** Refused, tampered with, or past its grace: demo until the next check-in. */
    data class Lapsed(val why: String) : TokenStatus
}

/**
 * The most a token allows. The cached config still has to allow a thing as
 * well: a token only ever narrows it, never widens it, so a seat released
 * after the token was issued, or a feature switched off since, still counts.
 */
data class TokenBound(
    val licensed: Boolean,
    val share: Boolean,
    val backup: Boolean,
    /** A ceiling on analyses, or null for none beyond the cached one. */
    val maxAnalyses: Int?,
)

/** What is on disk for the token, as the glue found it. */
sealed interface StoredEntitlement {
    data object Nothing : StoredEntitlement

    data object Tampered : StoredEntitlement

    data class Sealed(val record: SealedEntitlement.Record) : StoredEntitlement
}

/** What a `/v1/config`-shaped answer does to the stored token. */
enum class TokenIntake {
    /** Verify and keep the new token. */
    STORE,

    /** Leave the stored one alone (a lease answer that carries none). */
    KEEP,

    /** The backend answered without one: back to the unsigned cache alone. */
    CLEAR,

    /** This build pins no keys: tokens mean nothing to it. */
    IGNORE,
}

/** Pure decisions the Android glue ([EntitlementStore]) applies. */
object EntitlementPolicy {

    val UNBOUNDED = TokenBound(licensed = true, share = true, backup = true, maxAnalyses = null)

    /** What [status] allows; a lapsed token allows demo, with [demoMaxAnalyses]. */
    fun bound(status: TokenStatus, demoMaxAnalyses: Int): TokenBound = when (status) {
        TokenStatus.NotUsed -> UNBOUNDED
        is TokenStatus.Lapsed -> TokenBound(false, false, false, demoMaxAnalyses)
        is TokenStatus.Valid -> {
            val claims = status.claims
            val licensed = claims.isLicensed
            val ceiling = claims.features.maxAnalyses.takeIf { it > 0 }
            TokenBound(
                licensed = licensed,
                share = licensed && claims.features.share,
                backup = licensed && claims.features.backup,
                maxAnalyses = ceiling ?: if (licensed) null else demoMaxAnalyses,
            )
        }
    }

    /** [cachedCap] held to [bound]'s ceiling. */
    fun cap(cachedCap: Int, bound: TokenBound): Int = bound.maxAnalyses?.let { minOf(cachedCap, it) } ?: cachedCap

    /**
     * What to do with [token] from a backend answer. Only the `/v1/config`
     * answer is [authoritative] about a missing token; the lease routes return
     * the same shape and may predate tokens on the backend.
     */
    fun intake(pinsConfigured: Boolean, token: String?, authoritative: Boolean): TokenIntake = when {
        !pinsConfigured -> TokenIntake.IGNORE
        !token.isNullOrBlank() -> TokenIntake.STORE
        authoritative -> TokenIntake.CLEAR
        else -> TokenIntake.KEEP
    }

    /** The token's status [now], and the clock state to keep (null when there is none). */
    fun evaluate(
        stored: StoredEntitlement,
        verification: Verification?,
        now: DeviceTime,
    ): Pair<TokenStatus, ClockState?> = when (stored) {
        StoredEntitlement.Nothing -> TokenStatus.NotUsed to null
        StoredEntitlement.Tampered -> TokenStatus.Lapsed(GraceEnd.TAMPERED.name) to null
        is StoredEntitlement.Sealed -> when (verification) {
            is Verification.Verified -> {
                val claims = verification.claims
                val (clock, verdict) =
                    EntitlementClock.evaluate(stored.record.clock, now, claims.graceUntil, claims.exp)
                val status = when (verdict) {
                    GraceVerdict.Within -> TokenStatus.Valid(claims)
                    is GraceVerdict.Ended -> TokenStatus.Lapsed(verdict.why.name)
                }
                status to clock
            }
            is Verification.Rejected -> TokenStatus.Lapsed(verification.reason.name) to stored.record.clock
            null -> TokenStatus.Lapsed(RejectReason.MALFORMED.name) to stored.record.clock
        }
    }
}
