package com.sempermechanics.semper.data.account.entitlement

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put
import com.sempermechanics.semper.data.prefs.remove
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The phone side of entitlement tokens: where the token and its clock state
 * live, and the Keystore, clock and device readings the pure code needs.
 * Every rule is in [EntitlementPolicy], [EntitlementVerifier] and
 * [EntitlementClock]; this only reads and writes.
 *
 * A build with no pinned keys (`SEMPER_ENTITLEMENT_PUBKEYS` empty, every
 * debug and CI build) returns [TokenStatus.NotUsed] before touching the
 * Keystore or the disk, so it behaves exactly as builds before tokens did.
 *
 * Storage: one string in [PrefFiles.Entitlement], sealed with HMAC-SHA256
 * under a non-exportable Keystore key ([HMAC_ALIAS]). The key doubles as a
 * marker: it exists only while a token is stored, so a deleted record with the
 * key still present reads as tampered, not as "never had a token". The
 * verification and the clock live in memory after the first read in a
 * process, and the clock is written back at most once a minute, so a gate
 * check costs no ECDSA and no disk.
 */
object EntitlementStore {

    private const val HMAC_ALIAS = "SemperEntitlementHmac"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val HMAC = "HmacSHA256"
    private const val MS_PER_SECOND = 1000L
    private const val PERSIST_EVERY_MS = 60_000L

    private val pins: EntitlementPins by lazy {
        EntitlementPins.parse(BuildConfig.SEMPER_ENTITLEMENT_PUBKEYS).also {
            if (it.invalid.isNotEmpty()) Timber.w("Ignoring unusable entitlement pins: %s", it.invalid)
        }
    }

    /** A boot count cannot change while this process lives. */
    @Volatile
    private var bootCount: Int? = null

    private val lock = Any()

    /** Loaded on first use only: a build with no pins never opens the Keystore. */
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    /** What is stored, as last read or written; null until the first read in this process. */
    private var memory: StoredEntitlement? = null

    /** The last verification, keyed by its token, so the same token is not verified twice. */
    private var verified: Pair<String, Verification>? = null

    /** `elapsedRealtime` of the last write; starts a full interval back so the first one is due. */
    private var lastPersistElapsed = -PERSIST_EVERY_MS

    private val tagger = Tagger { data ->
        Mac.getInstance(HMAC).run {
            init(hmacKey())
            doFinal(data)
        }
    }

    /** The stored token's status now. */
    fun status(context: Context): TokenStatus {
        if (pins.isEmpty) return TokenStatus.NotUsed
        val appContext = context.applicationContext
        return synchronized(lock) {
            val now = deviceTime(appContext)
            val stored = memory ?: load(appContext).also { memory = it }
            val verification = (stored as? StoredEntitlement.Sealed)?.let { verify(appContext, it.record.token) }
            val (status, clock) = EntitlementPolicy.evaluate(stored, verification, now)
            if (stored is StoredEntitlement.Sealed && clock != null && clock != stored.record.clock) {
                val next = StoredEntitlement.Sealed(stored.record.copy(clock = clock))
                memory = next
                val urgent = clock.rolledBack != stored.record.clock.rolledBack
                if (urgent || now.elapsedMs - lastPersistElapsed >= PERSIST_EVERY_MS) save(appContext, next.record, now)
            }
            if (status is TokenStatus.Lapsed) Timber.d("Entitlement token lapsed: %s", status.why)
            status
        }
    }

    /**
     * A `/v1/config`-shaped answer arrived carrying [token] (or none). Only
     * the `/v1/config` answer is [authoritative] about a missing token.
     * [serverNowMs] is the server's clock as the API's `Date` header taught it,
     * when known; the token's `iat` is used too, whichever is later.
     */
    fun accept(context: Context, token: String?, authoritative: Boolean, serverNowMs: Long?) {
        val appContext = context.applicationContext
        when (EntitlementPolicy.intake(!pins.isEmpty, token, authoritative)) {
            TokenIntake.IGNORE, TokenIntake.KEEP -> Unit
            TokenIntake.CLEAR -> clear(appContext)
            TokenIntake.STORE -> synchronized(lock) { store(appContext, checkNotNull(token), serverNowMs) }
        }
    }

    /** Forget the token, its clock and its key (sign-out, or a backend that sends none). */
    fun clear(context: Context) {
        if (pins.isEmpty) return
        synchronized(lock) {
            privatePrefs(context, PrefFiles.Entitlement.NAME).edit { remove(PrefFiles.Entitlement.SEALED) }
            runCatching { keyStore.deleteEntry(HMAC_ALIAS) }
                .onFailure { Timber.w(it, "Could not drop the entitlement key") }
            memory = StoredEntitlement.Nothing
            verified = null
        }
    }

    private fun store(context: Context, token: String, serverNowMs: Long?) {
        val now = deviceTime(context)
        val verification = verify(context, token)
        if (verification is Verification.Rejected) Timber.w("Entitlement token refused: %s", verification.reason)
        val iatMs = (verification as? Verification.Verified)?.claims?.iat?.times(MS_PER_SECOND)
        val serverMs = maxOf(iatMs ?: 0L, serverNowMs ?: 0L).takeIf { it > 0L } ?: now.wallMs
        val previous = (memory ?: load(context)) as? StoredEntitlement.Sealed
        val clock = EntitlementClock.observeServer(previous?.record?.clock, serverMs, now)
        val record = SealedEntitlement.Record(token, clock)
        memory = StoredEntitlement.Sealed(record)
        save(context, record, now)
    }

    private fun load(context: Context): StoredEntitlement {
        val sealed = privatePrefs(context, PrefFiles.Entitlement.NAME)[PrefFiles.Entitlement.SEALED]
        return when {
            sealed == null && hasHmacKey() -> StoredEntitlement.Tampered.also { Timber.w("Entitlement record missing") }
            sealed == null -> StoredEntitlement.Nothing
            else -> when (val unsealed = SealedEntitlement.unseal(sealed, tagger)) {
                is SealedEntitlement.Unsealed.Ok -> StoredEntitlement.Sealed(unsealed.record)
                SealedEntitlement.Unsealed.Tampered -> StoredEntitlement.Tampered.also {
                    Timber.w("Entitlement record failed its integrity check")
                }
            }
        }
    }

    private fun save(context: Context, record: SealedEntitlement.Record, now: DeviceTime) {
        runCatching { SealedEntitlement.seal(record, tagger) }
            .onSuccess { sealed ->
                privatePrefs(context, PrefFiles.Entitlement.NAME).edit { put(PrefFiles.Entitlement.SEALED, sealed) }
                lastPersistElapsed = now.elapsedMs
            }
            .onFailure { Timber.w(it, "Could not seal the entitlement record") }
    }

    private fun verify(context: Context, token: String): Verification {
        verified?.takeIf { it.first == token }?.let { return it.second }
        val expected = runCatching {
            EntitlementVerifier.Expected(
                appId = EntitlementApp.shortId(BuildConfig.APPLICATION_ID),
                deviceId = DeviceKeys.deviceId(context),
                deviceKeyThumbprint = DeviceKeyThumbprint.of(DeviceKeys(context).publicKeyDer()),
            )
        }.onFailure { Timber.w(it, "Could not read the device key for the entitlement check") }.getOrNull()
        val result = expected?.let { EntitlementVerifier(pins).verify(token, it) }
            ?: Verification.Rejected(RejectReason.WRONG_DEVICE_KEY)
        verified = token to result
        return result
    }

    private fun deviceTime(context: Context): DeviceTime {
        val boot = bootCount ?: Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
            DeviceTime.UNKNOWN_BOOT,
        ).also { bootCount = it }
        return DeviceTime(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot)
    }

    private fun hasHmacKey(): Boolean = runCatching { keyStore.containsAlias(HMAC_ALIAS) }.getOrDefault(false)

    private fun hmacKey(): SecretKey {
        (keyStore.getKey(HMAC_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
    }
}
