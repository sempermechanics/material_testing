package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.AccountPhonesDto
import com.sempermechanics.semper.data.net.DeviceActivateResponse
import com.sempermechanics.semper.data.net.DeviceDeactivateResponse
import com.sempermechanics.semper.data.net.PhonesApi

/**
 * A hand-written [PhonesApi] for JVM tests, as [FakeCloudApi] is for
 * [com.sempermechanics.semper.data.net.CloudApi]: each call is recorded in
 * [calls], and an unscripted one fails the test.
 */
class FakePhonesApi(override var enabled: Boolean = true) : PhonesApi {

    val calls = mutableListOf<String>()

    var onActivate: suspend (String, Boolean) -> DeviceActivateResponse = { _, _ -> unscripted("activateDevice") }
    var onList: suspend (String) -> AccountPhonesDto = { unscripted("listDevices") }
    var onDeactivate: suspend (String, String) -> DeviceDeactivateResponse = { _, _ -> unscripted("deactivateDevice") }

    override suspend fun activateDevice(idToken: String, upgrade: Boolean): DeviceActivateResponse {
        calls += if (upgrade) "activateDevice(upgrade)" else "activateDevice"
        return onActivate(idToken, upgrade)
    }

    override suspend fun listDevices(idToken: String): AccountPhonesDto {
        calls += "listDevices"
        return onList(idToken)
    }

    override suspend fun deactivateDevice(idToken: String, deviceId: String): DeviceDeactivateResponse {
        calls += "deactivateDevice($deviceId)"
        return onDeactivate(idToken, deviceId)
    }

    private fun unscripted(name: String): Nothing = throw AssertionError("unexpected PhonesApi.$name call")
}
