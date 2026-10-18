package com.sempermechanics.semper.data.policy

/** Everything outside the licence that can hold a feature back. */
data class PolicyInputs(
    val killSwitches: Set<KillSwitch> = emptySet(),
    val managed: ManagedConfig = ManagedConfig.UNMANAGED,
    val version: VersionVerdict = VersionVerdict.Current,
)

/**
 * Pure gates over [PolicyInputs]. Each is one more condition on top of what
 * the licence allows (`LicenseEntitlements`), never a grant of its own: a
 * `true` here opens nothing the licence keeps closed.
 *
 * None of them touches what is already on the phone. Opening, viewing and
 * exporting a local analysis stay available whatever these say (share to
 * other apps is the [KillSwitch.SHARE] exception, by design).
 */
object FeatureGates {

    fun share(inputs: PolicyInputs): Boolean = KillSwitch.SHARE !in inputs.killSwitches

    /** Cloud backup's screens and the licensed half of cloud. */
    fun backup(inputs: PolicyInputs): Boolean =
        KillSwitch.BACKUP !in inputs.killSwitches && inputs.managed.cloudBackupAllowed

    /**
     * Whether analyses are recorded to the cloud at all. Only device policy
     * says no for good; an analysis saved while it does is "not backed up".
     */
    fun recordsToCloud(inputs: PolicyInputs): Boolean = inputs.managed.cloudBackupAllowed

    /** Whether an upload may be queued now; one that may not stays waiting. */
    fun upload(inputs: PolicyInputs): Boolean =
        backup(inputs) && KillSwitch.UPLOAD !in inputs.killSwitches && inputs.version !is VersionVerdict.Required

    fun restore(inputs: PolicyInputs): Boolean =
        backup(inputs) && KillSwitch.RESTORE !in inputs.killSwitches && inputs.version !is VersionVerdict.Required

    fun newAnalysis(inputs: PolicyInputs): Boolean = inputs.version !is VersionVerdict.Required
}
