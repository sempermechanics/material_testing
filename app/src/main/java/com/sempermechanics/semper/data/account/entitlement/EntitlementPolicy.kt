package com.sempermechanics.semper.data.account.entitlement

/** What the stored token means for the app right now. */
sealed interface TokenStatus {
    /**
     * No token in play: this build pins no keys, the backend sends none, or
     * the one it sent does not verify here (a `kid` this build does not pin,
     * a signature, binding or claim that does not check out). The unsigned
     * `/v1/config` cache alone decides, exactly as before tokens.
     */
    data object NotUsed : TokenStatus

    /** Verified, bound to this device, and inside its offline grace. */
    data class Valid(val claims: EntitlementClaims) : TokenStatus

    /** Tampered with, or past its grace: demo until the next check-in. */
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

/** Whether the Keystore holds the record's HMAC key, as far as the glue can tell. */
enum class KeyPresence { PRESENT, ABSENT, UNREADABLE }

/** What to make of the record, its key and the first-seal mark found on disk ([EntitlementPolicy.reading]). */
enum class StoreReading {
    /** Nothing stored, or nothing that can be read now: no token. */
    NOTHING,

    /** A record and its key: check the record's MAC. */
    UNSEAL,

    /**
     * A record with no key to check it: the Keystore lost the key, or a clear
     * stopped between the two. Drop the record; no token, so the cached config
     * decides until the next `/v1/config` seals a fresh one.
     */
    DROP_RECORD,

    /**
     * A key with no record while a first seal was in flight: the process died
     * between making the key and writing the record. Drop the key; no token.
     */
    DROP_KEY,

    /** A key with no record and no seal in flight: the record was deleted. */
    TAMPERED,
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
     * The ceiling a screen shows: [serverMax] (the cached `maxSessions`) held
     * to [bound], the same number [cap] enforces once the server has reported
     * one; 0 while it has not, which the screens read as "unknown". A token
     * past its grace shows demo's cap, not the licence's.
     */
    fun shownCap(serverMax: Int, bound: TokenBound): Int = if (serverMax > 0) cap(serverMax, bound) else 0

    /**
     * Whether a floating-seat account must take a seat before starting work:
     * the server no longer counts it licensed ([serverLicensed] false), or its
     * token ran past `graceUntil` or `exp`, which on a floating seat end with
     * the lease it was issued under. A token that lapsed for another reason
     * (a clock rollback, an altered record) means demo's limits, not a seat;
     * with no token the server's answer alone decides, as before tokens.
     */
    fun seatRequired(serverLicensed: Boolean, status: TokenStatus): Boolean =
        !serverLicensed || (status is TokenStatus.Lapsed && status.why in LEASE_ENDED)

    private val LEASE_ENDED = setOf(GraceEnd.GRACE_OVER.name, GraceEnd.EXPIRED.name)

    /**
     * What the stored pieces mean. Only a key with no record and no first
     * seal in flight reads as tampering; a lost key, a clear cut short, or a
     * crash between making the key and writing the record reads as no token.
     * A Keystore that cannot be read is no token too, and nothing is deleted.
     */
    fun reading(hasRecord: Boolean, key: KeyPresence, sealing: Boolean): StoreReading = when {
        key == KeyPresence.UNREADABLE -> StoreReading.NOTHING
        hasRecord && key == KeyPresence.PRESENT -> StoreReading.UNSEAL
        hasRecord -> StoreReading.DROP_RECORD
        key == KeyPresence.ABSENT -> StoreReading.NOTHING
        sealing -> StoreReading.DROP_KEY
        else -> StoreReading.TAMPERED
    }

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
            // A token that does not verify counts as no token, not as a lapsed
            // one. It can only have come from the backend (the record is
            // sealed), so refusing it gains nothing a stripped token would not
            // give away, while lapsing it would drop every licensed user to
            // demo, online too, the moment the signing key and this build's
            // pins disagree (a mis-provisioned key, a rotation shipped before
            // its pin) or the device key cannot be read.
            is Verification.Rejected, null -> TokenStatus.NotUsed to stored.record.clock
        }
    }
}
