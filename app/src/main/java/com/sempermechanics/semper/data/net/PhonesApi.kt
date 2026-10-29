package com.sempermechanics.semper.data.net

/**
 * The account's phones on its licence for this app (ADR-033): Activate this
 * phone, list the account's phones, Deactivate one. [SemperApi] implements it;
 * tests pass a fake (as [CloudApi], ADR-002), so the many [CloudApi] fakes need
 * not learn these routes.
 */
interface PhonesApi {
    /** A backend is configured and the debug sign-in bypass is off ([CloudApi.enabled]). */
    val enabled: Boolean

    /**
     * `POST /v1/me/devices/activate`, signed by this phone: license it on the
     * account's licence for this app, or with [upgrade] on the new licence
     * issued to the account, which moves the account there.
     */
    suspend fun activateDevice(idToken: String, upgrade: Boolean = false): DeviceActivateResponse

    /** `GET /v1/me/devices`: the account's phones for this app, and its licence's count. */
    suspend fun listDevices(idToken: String): AccountPhonesDto

    /** `DELETE /v1/me/devices/{deviceId}`, signed: stop licensing one of the account's phones. */
    suspend fun deactivateDevice(idToken: String, deviceId: String): DeviceDeactivateResponse
}
