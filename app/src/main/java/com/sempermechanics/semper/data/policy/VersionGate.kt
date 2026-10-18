package com.sempermechanics.semper.data.policy

/** How this build stands against the versions the backend asks for. */
sealed interface VersionVerdict {
    /** At or past both, or the backend names neither. */
    data object Current : VersionVerdict

    /**
     * Below `recommendedVersionCode`: still fully usable. [nudge] is true the
     * first time this pair of versions is seen, and [nudgeKey] is what to
     * remember once the nudge has been shown.
     */
    data class Recommended(val recommended: Int, val nudge: Boolean, val nudgeKey: String) : VersionVerdict

    /**
     * Below `minSupportedVersionCode`. The analyses on the phone stay
     * readable and exportable; starting a new one, and cloud upload and
     * restore, wait for the update.
     */
    data class Required(val minimum: Int) : VersionVerdict
}

/** Pure version-gate decision (A6.1); `AppPolicy` reads the inputs. */
object VersionGate {

    /**
     * [versionCode] (`BuildConfig.VERSION_CODE`) against the backend's
     * [minSupported] and [recommended]. A missing or non-positive value is not
     * a requirement: an older backend sends neither. [lastNudge] is the
     * [VersionVerdict.Recommended.nudgeKey] stored after the last nudge, so the
     * banner shows once for each installed version and recommendation.
     */
    fun decide(versionCode: Int, minSupported: Int?, recommended: Int?, lastNudge: String?): VersionVerdict = when {
        minSupported != null && minSupported > 0 && versionCode < minSupported -> VersionVerdict.Required(minSupported)
        recommended != null && recommended > 0 && versionCode < recommended -> {
            val key = "$versionCode<$recommended"
            VersionVerdict.Recommended(recommended, nudge = key != lastNudge, nudgeKey = key)
        }
        else -> VersionVerdict.Current
    }
}
