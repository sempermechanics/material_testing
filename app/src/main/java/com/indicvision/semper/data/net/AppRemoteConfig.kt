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
object AppRemoteConfig {

    private const val PREFS = "indic_remote_config"
    private const val K_MAX_SESSIONS = "max_sessions"
    private const val K_MAX_FILES = "max_files_per_session"
    private const val K_MAX_FRAMES = "max_frames"

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
        }
    }

    /** True once the backend has reported a positive maxSessions. */
    fun isKnown(context: Context): Boolean = maxSessions(context) > 0

    fun maxSessions(context: Context): Int = prefs(context).getInt(K_MAX_SESSIONS, 0)

    fun maxFilesPerSession(context: Context): Int = prefs(context).getInt(K_MAX_FILES, 0)

    /** Deformed-frame ceiling from cloud; 0 until config is known. */
    fun maxFrames(context: Context): Int = prefs(context).getInt(K_MAX_FRAMES, 0)

    /** Drop cached limits (sign-out). */
    fun clear(context: Context) = prefs(context).edit { clear() }
}
