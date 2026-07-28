package com.rafad.indicvisiondic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Behavioral settings surfaced in the Home settings drawer. Plain
 * SharedPreferences — read at the point of use, no caching layer.
 */
object DicSettings {

    const val DEFAULT_MAX_FRAMES = 50
    const val MIN_MAX_FRAMES = 10

    /** Hard ceiling: an analysis may use at most this many deformed images. */
    const val MAX_MAX_FRAMES = 150

    private const val PREFS = "dic_settings"
    private const val KEY_SCHEMA = "schema"

    /** Bump when a key is retired, and drop it in [migrate]. */
    private const val SCHEMA_VERSION = 1
    private const val KEY_SAVE_TO_CLOUD = "save_to_cloud"
    private const val KEY_UPLOAD_WIFI_ONLY = "upload_wifi_only"
    private const val KEY_MAX_FRAMES = "max_frames"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Drops preferences whose setting no longer exists, so an upgraded device
     * does not carry values nothing reads. Runs once per schema bump.
     */
    fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION) return
        prefs.edit {
            // "Keep every re-run" — the toggle is gone; one row per set of inputs.
            remove("keep_every_rerun")
            putInt(KEY_SCHEMA, SCHEMA_VERSION)
        }
    }

    /** Master switch for the upload worker; off = sessions stay "local only". */
    fun saveToCloud(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE_TO_CLOUD, true)

    fun setSaveToCloud(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SAVE_TO_CLOUD, value).apply()

    /**
     * When true, uploads (post-analysis and reconcile repair) wait for unmetered
     * Wi‑Fi. Default false = any connected network.
     */
    fun uploadWifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_UPLOAD_WIFI_ONLY, false)

    fun setUploadWifiOnly(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_UPLOAD_WIFI_ONLY, value).apply()

    /** Cap on deformed frames per analysis (picker + video extraction). */
    fun maxFrames(context: Context): Int = prefs(context).getInt(KEY_MAX_FRAMES, DEFAULT_MAX_FRAMES)
        .coerceIn(MIN_MAX_FRAMES, MAX_MAX_FRAMES)

    fun setMaxFrames(context: Context, value: Int) = prefs(context).edit()
        .putInt(KEY_MAX_FRAMES, value.coerceIn(MIN_MAX_FRAMES, MAX_MAX_FRAMES))
        .apply()
}
