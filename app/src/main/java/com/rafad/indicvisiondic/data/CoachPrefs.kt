package com.rafad.indicvisiondic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** First-visit coach-mark "seen" flags. One prefs file, one key per screen. */
object CoachPrefs {

    private const val PREFS = "indic_coach"
    private const val KEY_HOME = "coach_home_seen"
    private const val KEY_ANALYSIS_IMAGES = "coach_analysis_images_seen"
    private const val KEY_ANALYSIS_SETTINGS = "coach_analysis_settings_seen"
    private const val KEY_ANALYSIS_SWEEP = "coach_analysis_sweep_seen"

    enum class Screen {
        HOME,
        ANALYSIS_IMAGES,
        ANALYSIS_SETTINGS,
        ANALYSIS_SWEEP,
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(screen: Screen): String = when (screen) {
        Screen.HOME -> KEY_HOME
        Screen.ANALYSIS_IMAGES -> KEY_ANALYSIS_IMAGES
        Screen.ANALYSIS_SETTINGS -> KEY_ANALYSIS_SETTINGS
        Screen.ANALYSIS_SWEEP -> KEY_ANALYSIS_SWEEP
    }

    fun hasSeen(context: Context, screen: Screen): Boolean =
        prefs(context).getBoolean(key(screen), false)

    fun markSeen(context: Context, screen: Screen) {
        prefs(context).edit { putBoolean(key(screen), true) }
    }
}
