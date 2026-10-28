package com.sempermechanics.semper.data.account.entitlement

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.privatePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The pinned entitlement path on a real Keystore (readiness TE4-101): every CI
 * build pins no keys, so EntitlementStore returned before the Keystore HMAC
 * seal, the real device-key thumbprint and the boot-count clock ever ran. Here
 * a test-only P-256 key is pinned ([EntitlementStore.testPins]) and a token is
 * signed for this install's own device key and id, as the backend would.
 */
@RunWith(AndroidJUnit4::class)
class EntitlementStoreDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val signer: KeyPair = p256()
    private val pins = EntitlementPins.parse("$KID:" + b64(signer.public.encoded))

    @Before
    fun pinTestKey() {
        EntitlementStore.testPins = pins
        EntitlementStore.clear(context)
    }

    @After
    fun unpin() {
        EntitlementStore.clear(context)
        EntitlementStore.testPins = null
    }

    /** Forget the in-memory record, as a new process would. */
    private fun reload() {
        EntitlementStore.testPins = pins
    }

    @Test
    fun aTokenForThisDeviceIsValidAndSealed() {
        EntitlementStore.accept(context, token(), authoritative = true, serverNowMs = null)

        val status = EntitlementStore.status(context)
        assertTrue("status $status", status is TokenStatus.Valid)
        assertEquals("licensed", (status as TokenStatus.Valid).claims.mode)
        // Read back from disk through the Keystore seal, not from memory.
        reload()
        assertTrue(EntitlementStore.status(context) is TokenStatus.Valid)
    }

    @Test
    fun anEditedSealedRecordReadsAsTampered() {
        EntitlementStore.accept(context, token(), authoritative = true, serverNowMs = null)
        val prefs = privatePrefs(context, PrefFiles.Entitlement.NAME)
        val sealed = checkNotNull(prefs.getString(PrefFiles.Entitlement.SEALED.name, null)) { "nothing sealed" }
        val i = sealed.length / 2
        val edited = sealed.substring(0, i) + (if (sealed[i] == 'A') 'B' else 'A') + sealed.substring(i + 1)
        assertTrue(prefs.edit().putString(PrefFiles.Entitlement.SEALED.name, edited).commit())

        reload()

        assertEquals(TokenStatus.Lapsed(GraceEnd.TAMPERED.name), EntitlementStore.status(context))
    }

    @Test
    fun aTokenForAnotherDeviceKeyChangesNothing() {
        val elsewhere = DeviceKeyThumbprint.of(p256().public.encoded)

        EntitlementStore.accept(context, token(dkt = elsewhere), authoritative = true, serverNowMs = null)

        assertEquals(TokenStatus.NotUsed, EntitlementStore.status(context))
    }

    /** A token as the backend signs it, for this install unless [dkt] says otherwise. */
    private fun token(dkt: String = DeviceKeyThumbprint.of(DeviceKeys(context).publicKeyDer())): String {
        val iat = System.currentTimeMillis() / 1000
        val header = """{"alg":"ES256","kid":"$KID","typ":"semper-ent+jws"}"""
        val claims = """{"iss":"semper-api","sub":"uid-device-test",""" +
            """"app":"${EntitlementApp.shortId(BuildConfig.APPLICATION_ID)}",""" +
            """"dev":"${DeviceKeys.deviceId(context)}","dkt":"$dkt","mode":"licensed",""" +
            """"features":{"share":true,"backup":true,"maxAnalyses":500},"lic":"lic-device-test",""" +
            """"iat":$iat,"refreshAfter":${iat + DAY_S},"graceUntil":${iat + GRACE_S},"exp":${iat + GRACE_S}}"""
        val input = Base64Url.encode(header.toByteArray()) + "." + Base64Url.encode(claims.toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return input + "." + Base64Url.encode(derToRaw(der))
    }

    private companion object {
        const val KID = "device-test"
        const val DAY_S = 86_400L
        const val GRACE_S = 14 * DAY_S

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        /** DER `SEQUENCE { INTEGER r, INTEGER s }` to raw `r || s`, 32 bytes each. */
        fun derToRaw(der: ByteArray): ByteArray {
            var at = 2
            fun next(): ByteArray {
                check(der[at] == 0x02.toByte())
                val len = der[at + 1].toInt()
                val value = der.copyOfRange(at + 2, at + 2 + len)
                at += 2 + len
                val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
                return ByteArray(32 - trimmed.size) + trimmed
            }
            return next() + next()
        }
    }
}
