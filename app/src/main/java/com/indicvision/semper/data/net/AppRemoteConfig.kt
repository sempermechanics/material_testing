package com.indicvision.semper.data.net

import android.content.Context
import androidx.core.content.edit

/**
 * Cached product limits from [GET /v1/config](IndicApi.getConfig).
 *
 * Source of truth is the backend (per-user Firestore fields → fleet defaults).
 * The app never invents a session quota locally — until a successful fetch,
 * [isKnown] is false and cloud-backed gates fail closed.
 *
 * This is the **sole owner of the limit ceilings**. [TokenStore] reads the
 * session ceiling from here ([maxSessions]) rather than caching its own copy, so
 * the dependency runs one way (TokenStore → AppRemoteConfig) with no cycle. This
 * object never calls back into TokenStore.
 */
@Suppress("TooManyFunctions")
object AppRemoteConfig {

    private const val PREFS = "indic_remote_config"
    private const val K_MAX_SESSIONS = "max_sessions"
    private const val K_MAX_FILES = "max_files_per_session"
    private const val K_MAX_FRAMES = "max_frames"
    private const val K_DAT_CODEC_ENCODING = "dat_codec_encoding_enabled"
    private const val K_PLAN = "plan"
    private const val K_CLOUD_BACKUP = "cloud_backup_enabled"
    private const val K_SHARE = "share_enabled"
    private const val K_LICENSE_PREFIX = "license_prefix"
    private const val K_LICENSE_KIND = "license_kind"
    private const val K_FAIL_STREAK = "config_fail_streak"
    private const val FAIL_STREAK_HINT = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Persist a successful config response. The session-limit hard stop is
     * recomputed live by [TokenStore.isSessionLimitReached] from the used count
     * against [maxSessions], so storing the ceiling here is all that is needed —
     * no write back into TokenStore, no [localSessionCount] to fold in.
     */
    fun apply(context: Context, config: AppConfigDto) {
        prefs(context).edit {
            putInt(K_MAX_SESSIONS, config.maxSessions.coerceAtLeast(0))
            putInt(K_MAX_FILES, config.maxFilesPerSession.coerceAtLeast(0))
            putInt(K_MAX_FRAMES, config.maxFrames.coerceAtLeast(0))
            putBoolean(K_DAT_CODEC_ENCODING, config.datCodecEncodingEnabled)
            putString(K_PLAN, config.plan.ifBlank { "demo" })
            putBoolean(K_CLOUD_BACKUP, config.cloudBackupEnabled)
            putBoolean(K_SHARE, config.shareEnabled)
            putString(K_LICENSE_PREFIX, config.licensePrefix)
            putString(K_LICENSE_KIND, config.licenseKind)
            putInt(K_FAIL_STREAK, 0)
        }
    }

    /** Record a failed /v1/config fetch (uploads stay gated until config lands). */
    fun recordFetchFailure(context: Context) {
        val prefs = prefs(context)
        prefs.edit { putInt(K_FAIL_STREAK, prefs.getInt(K_FAIL_STREAK, 0) + 1) }
    }

    /**
     * True when several consecutive config fetches failed while the quota is
     * still unknown — surface a "can't sync yet" hint so this is not silent.
     */
    fun shouldHintSyncBlocked(context: Context): Boolean =
        !isKnown(context) && prefs(context).getInt(K_FAIL_STREAK, 0) >= FAIL_STREAK_HINT

    /** True once the backend has reported a positive maxSessions. */
    fun isKnown(context: Context): Boolean = maxSessions(context) > 0

    fun maxSessions(context: Context): Int = prefs(context).getInt(K_MAX_SESSIONS, 0)

    fun maxFilesPerSession(context: Context): Int = prefs(context).getInt(K_MAX_FILES, 0)

    /** Deformed-frame ceiling from cloud; 0 until config is known. */
    fun maxFrames(context: Context): Int = prefs(context).getInt(K_MAX_FRAMES, 0)

    /**
     * Whether this account may upload `.dat` entries through [DatCodec][com.indicvision.semper.data.DatCodec].
     * Fails closed like everything else here — `false` (today's raw behaviour)
     * until a successful [apply] says otherwise, so a device that has never
     * synced config, or whose last fetch failed, never guesses "on".
     */
    fun datCodecEncodingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(K_DAT_CODEC_ENCODING, false)

    fun plan(context: Context): String = prefs(context).getString(K_PLAN, "demo") ?: "demo"

    fun cloudBackupEnabled(context: Context): Boolean =
        prefs(context).getBoolean(K_CLOUD_BACKUP, false)

    fun shareEnabled(context: Context): Boolean = prefs(context).getBoolean(K_SHARE, false)

    fun licensePrefix(context: Context): String =
        prefs(context).getString(K_LICENSE_PREFIX, "") ?: ""

    /** `""`, `"individual"`, or `"campus"` — display/support metadata only. */
    fun licenseKind(context: Context): String =
        prefs(context).getString(K_LICENSE_KIND, "") ?: ""

    /** Drop cached limits (sign-out). */
    fun clear(context: Context) = prefs(context).edit { clear() }
}
