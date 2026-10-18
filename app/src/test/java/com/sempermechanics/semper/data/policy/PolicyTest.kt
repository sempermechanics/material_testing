package com.sempermechanics.semper.data.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kill switches (A6.4), the version gate (A6.1), managed configuration (A5.6) and the gates over them. */
class PolicyTest {

    @Test
    fun `kill switches parse by wire name and ignore the unknown`() {
        assertEquals(
            setOf(KillSwitch.BACKUP, KillSwitch.SHARE, KillSwitch.RESTORE, KillSwitch.UPLOAD),
            KillSwitch.parse(listOf("backup", " Share ", "RESTORE", "upload", "teleport", "")),
        )
        assertEquals(emptySet<KillSwitch>(), KillSwitch.parse(null))
        assertEquals(emptySet<KillSwitch>(), KillSwitch.parse(emptyList()))
        assertEquals(setOf("share", "upload"), KillSwitch.wireNames(setOf(KillSwitch.SHARE, KillSwitch.UPLOAD)))
        assertEquals(setOf(KillSwitch.SHARE), KillSwitch.parse(KillSwitch.wireNames(setOf(KillSwitch.SHARE))))
    }

    @Test
    fun `below the minimum an update is required`() {
        val verdict = VersionGate.decide(35, minSupported = 40, recommended = 45, lastNudge = null)
        assertEquals(VersionVerdict.Required(40), verdict)
    }

    @Test
    fun `at the minimum it is not`() {
        val verdict = VersionGate.decide(40, minSupported = 40, recommended = null, lastNudge = null)
        assertTrue(verdict is VersionVerdict.Current)
    }

    @Test
    fun `below the recommendation it nudges once per version pair`() {
        val first = VersionGate.decide(35, minSupported = 30, recommended = 36, lastNudge = null)
        assertEquals(VersionVerdict.Recommended(36, nudge = true, nudgeKey = "35<36"), first)
        val again = VersionGate.decide(35, minSupported = 30, recommended = 36, lastNudge = "35<36")
        assertEquals(VersionVerdict.Recommended(36, nudge = false, nudgeKey = "35<36"), again)
        val raised = VersionGate.decide(35, minSupported = 30, recommended = 37, lastNudge = "35<36")
        assertTrue((raised as VersionVerdict.Recommended).nudge)
        val updatedButStillBehind = VersionGate.decide(36, minSupported = 30, recommended = 37, lastNudge = "35<37")
        assertTrue((updatedButStillBehind as VersionVerdict.Recommended).nudge)
    }

    @Test
    fun `no values or zero values gate nothing`() {
        assertEquals(VersionVerdict.Current, VersionGate.decide(1, null, null, null))
        assertEquals(VersionVerdict.Current, VersionGate.decide(1, 0, 0, null))
        assertEquals(VersionVerdict.Current, VersionGate.decide(1, -5, -5, null))
        assertEquals(VersionVerdict.Current, VersionGate.decide(50, 40, 45, null))
    }

    @Test
    fun `managed config maps typed values and drops bad ones`() {
        val config = ManagedConfig.from(
            mapOf(
                "accountEmailDomain" to " @Example.EDU ",
                "cloudBackupAllowed" to false,
                "backupOnUnmeteredOnly" to true,
            ),
        )
        val expected = ManagedConfig(
            accountEmailDomain = "example.edu",
            cloudBackupAllowed = false,
            backupOnUnmeteredOnly = true,
        )
        assertEquals(expected, config)

        val bad = ManagedConfig.from(
            mapOf(
                "accountEmailDomain" to "not a domain",
                "cloudBackupAllowed" to "false",
                "backupOnUnmeteredOnly" to 1,
            ),
        )
        assertEquals(ManagedConfig.UNMANAGED, bad)
        assertEquals(ManagedConfig.UNMANAGED, ManagedConfig.from(emptyMap()))
        assertEquals(null, ManagedConfig.from(mapOf("accountEmailDomain" to "")).accountEmailDomain)
        assertEquals("a-b.co.uk", ManagedConfig.from(mapOf("accountEmailDomain" to "a-b.co.uk")).accountEmailDomain)
    }

    @Test
    fun `an unmanaged phone with no switches and a current build allows everything`() {
        val open = PolicyInputs()
        assertTrue(FeatureGates.share(open))
        assertTrue(FeatureGates.backup(open))
        assertTrue(FeatureGates.recordsToCloud(open))
        assertTrue(FeatureGates.upload(open))
        assertTrue(FeatureGates.restore(open))
        assertTrue(FeatureGates.newAnalysis(open))
    }

    @Test
    fun `each kill switch closes its own feature`() {
        val share = PolicyInputs(killSwitches = setOf(KillSwitch.SHARE))
        assertFalse(FeatureGates.share(share))
        assertTrue(FeatureGates.backup(share))

        val upload = PolicyInputs(killSwitches = setOf(KillSwitch.UPLOAD))
        assertFalse(FeatureGates.upload(upload))
        assertTrue(FeatureGates.restore(upload))
        assertTrue(FeatureGates.recordsToCloud(upload))

        val restore = PolicyInputs(killSwitches = setOf(KillSwitch.RESTORE))
        assertFalse(FeatureGates.restore(restore))
        assertTrue(FeatureGates.upload(restore))

        val backup = PolicyInputs(killSwitches = setOf(KillSwitch.BACKUP))
        assertFalse(FeatureGates.backup(backup))
        assertFalse(FeatureGates.upload(backup))
        assertFalse(FeatureGates.restore(backup))
        assertTrue(FeatureGates.share(backup))
        assertTrue(FeatureGates.newAnalysis(backup))
    }

    @Test
    fun `a managed phone without cloud backup records nothing`() {
        val managed = PolicyInputs(managed = ManagedConfig(cloudBackupAllowed = false))
        assertFalse(FeatureGates.backup(managed))
        assertFalse(FeatureGates.recordsToCloud(managed))
        assertFalse(FeatureGates.upload(managed))
        assertFalse(FeatureGates.restore(managed))
        assertTrue(FeatureGates.share(managed))
    }

    @Test
    fun `an update required stops new work and transfers, not reading or sharing`() {
        val old = PolicyInputs(version = VersionVerdict.Required(40))
        assertFalse(FeatureGates.newAnalysis(old))
        assertFalse(FeatureGates.upload(old))
        assertFalse(FeatureGates.restore(old))
        assertTrue(FeatureGates.share(old))
        assertTrue(FeatureGates.backup(old))
        val nudged = PolicyInputs(version = VersionVerdict.Recommended(40, nudge = true, nudgeKey = "k"))
        assertTrue(FeatureGates.newAnalysis(nudged))
        assertTrue(FeatureGates.upload(nudged))
    }
}
