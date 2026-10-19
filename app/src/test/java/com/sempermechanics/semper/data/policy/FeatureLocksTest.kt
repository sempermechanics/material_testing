package com.sempermechanics.semper.data.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/** Locked, not hidden: which licensed features show locked, paused, hidden or open. */
class FeatureLocksTest {

    private fun all(access: FeatureAccess) = SettingsLocks(access, access, access)

    @Test
    fun `a licence with backup opens every backup section`() {
        assertEquals(all(FeatureAccess.OPEN), FeatureLocks.settings(backupLicensed = true, backupAllowed = true))
    }

    @Test
    fun `a demo account sees the backup sections locked, not hidden`() {
        val locks = FeatureLocks.settings(backupLicensed = false, backupAllowed = true)
        assertEquals(FeatureAccess.LOCKED, locks.cloud)
        assertEquals(FeatureAccess.LOCKED, locks.analysesData)
        assertEquals(FeatureAccess.LOCKED, locks.storageFreeUp)
    }

    @Test
    fun `backup held back by policy stays hidden, licensed or not`() {
        // The backup kill switch, or an EMM's cloudBackupAllowed=false: not a licence lock.
        assertEquals(all(FeatureAccess.HIDDEN), FeatureLocks.settings(backupLicensed = true, backupAllowed = false))
        assertEquals(all(FeatureAccess.HIDDEN), FeatureLocks.settings(backupLicensed = false, backupAllowed = false))
    }

    @Test
    fun `share is open only when licensed and allowed`() {
        assertEquals(FeatureAccess.OPEN, FeatureLocks.share(shareLicensed = true, shareAllowed = true))
    }

    @Test
    fun `share without the licence is locked, whatever the kill switch says`() {
        assertEquals(FeatureAccess.LOCKED, FeatureLocks.share(shareLicensed = false, shareAllowed = true))
        assertEquals(FeatureAccess.LOCKED, FeatureLocks.share(shareLicensed = false, shareAllowed = false))
    }

    @Test
    fun `the share kill switch pauses a licensed account's sharing, never locks it`() {
        assertEquals(FeatureAccess.PAUSED, FeatureLocks.share(shareLicensed = true, shareAllowed = false))
    }

    @Test
    fun `the gates feed it as the viewer and Settings do`() {
        val switchedOff = PolicyInputs(killSwitches = setOf(KillSwitch.SHARE, KillSwitch.BACKUP))
        assertEquals(FeatureAccess.PAUSED, FeatureLocks.share(true, FeatureGates.share(switchedOff)))
        assertEquals(all(FeatureAccess.HIDDEN), FeatureLocks.settings(true, FeatureGates.backup(switchedOff)))

        val noCloud = PolicyInputs(managed = ManagedConfig(cloudBackupAllowed = false))
        assertEquals(all(FeatureAccess.HIDDEN), FeatureLocks.settings(false, FeatureGates.backup(noCloud)))
        assertEquals(all(FeatureAccess.LOCKED), FeatureLocks.settings(false, FeatureGates.backup(PolicyInputs())))
    }
}
