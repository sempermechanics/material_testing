package com.sempermechanics.semper.ui.limit

/**
 * Where an Update button sends the user (TD-268): the Play listing for a
 * build Play installed, otherwise the page the build was downloaded from.
 * Builds are distributed from GitHub Releases as well as Play, and a
 * sideloaded build has no Play listing to update from.
 */
object UpdateSource {

    /** The installer package name of the Google Play Store. */
    const val PLAY_STORE = "com.android.vending"

    /**
     * The release download page for builds not installed by Play. Every
     * release so far is a pre-release, so this is the list, not `/latest`
     * (which skips pre-releases).
     */
    const val RELEASES_URL = "https://github.com/sempermechanics/semperdic-app/releases"

    /**
     * The pages to try, in order, for [packageName] installed by
     * [installingPackage] (`InstallSourceInfo.installingPackageName`, null
     * when unknown): the store app's listing then its web page for a Play
     * install, the release page otherwise. All but the last are tried
     * quietly; the last one is the fallback.
     */
    fun urls(packageName: String, installingPackage: String?): List<String> =
        if (installingPackage == PLAY_STORE) {
            listOf(
                "market://details?id=$packageName",
                "https://play.google.com/store/apps/details?id=$packageName",
            )
        } else {
            listOf(RELEASES_URL)
        }
}
