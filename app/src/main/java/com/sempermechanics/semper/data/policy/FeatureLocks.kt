package com.sempermechanics.semper.data.policy

/** How a licensed feature appears on a screen. */
enum class FeatureAccess {
    /** Licensed and allowed: it works. */
    OPEN,

    /**
     * Not in this account's licence: shown, disabled, with a lock and the one
     * licence note. Never a price, a purchase or a link to either.
     */
    LOCKED,

    /** Licensed, but a kill switch has it off for now: shown greyed, "temporarily unavailable". */
    PAUSED,

    /** Device or fleet policy keeps it off: not shown, as before locks. */
    HIDDEN,
}

/** What Settings shows of cloud backup and the storage rows that depend on it. */
data class SettingsLocks(
    /** Cloud backup: the save-to-cloud and Wi-Fi-only switches. */
    val cloud: FeatureAccess,
    /** Analyses data: each analysis's backup, restore and Save-to-Files download. */
    val analysesData: FeatureAccess,
    /** Storage's Free-up button and Auto-free row, which release backed-up local copies. */
    val storageFreeUp: FeatureAccess,
)

/**
 * Locked, not hidden. A feature the account's licence lacks stays on screen,
 * disabled, so a demo account can see what a seat or a licence would add; a
 * feature policy holds back keeps the behaviour it had before (hidden in
 * Settings, greyed in the viewer), because a kill switch or an EMM setting
 * says nothing about the licence.
 *
 * Pure: callers pass `LicenseEntitlements.backupLicensed` / `shareLicensed`
 * and the [FeatureGates] answers ([AppPolicy]).
 */
object FeatureLocks {

    /**
     * Settings' backup sections. Policy first: with backup switched off for
     * the fleet or by the EMM there is nothing to lock, so they stay hidden
     * as they always were. Otherwise a licence without backup locks them.
     */
    fun settings(backupLicensed: Boolean, backupAllowed: Boolean): SettingsLocks {
        val access = when {
            !backupAllowed -> FeatureAccess.HIDDEN
            !backupLicensed -> FeatureAccess.LOCKED
            else -> FeatureAccess.OPEN
        }
        return SettingsLocks(cloud = access, analysesData = access, storageFreeUp = access)
    }

    /**
     * The viewer's Share button, never hidden. The licence first: without it
     * the share kill switch going off would change nothing, so the lock is
     * what to say; with it, a kill switch only pauses sharing.
     */
    fun share(shareLicensed: Boolean, shareAllowed: Boolean): FeatureAccess = when {
        !shareLicensed -> FeatureAccess.LOCKED
        !shareAllowed -> FeatureAccess.PAUSED
        else -> FeatureAccess.OPEN
    }
}
