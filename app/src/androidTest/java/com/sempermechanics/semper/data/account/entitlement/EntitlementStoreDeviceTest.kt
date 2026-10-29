package com.sempermechanics.semper.data.account.entitlement

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.privatePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
    private val tokens = TestEntitlementTokens(context)
    private val pins = tokens.pins

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
        EntitlementStore.accept(context, tokens.token(), authoritative = true, serverNowMs = null)

        val status = EntitlementStore.status(context)
        assertTrue("status $status", status is TokenStatus.Valid)
        assertEquals("licensed", (status as TokenStatus.Valid).claims.mode)
        // Read back from disk through the Keystore seal, not from memory.
        reload()
        assertTrue(EntitlementStore.status(context) is TokenStatus.Valid)
    }

    @Test
    fun anEditedSealedRecordReadsAsTampered() {
        EntitlementStore.accept(context, tokens.token(), authoritative = true, serverNowMs = null)
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
        val elsewhere = DeviceKeyThumbprint.of(TestEntitlementTokens.p256().public.encoded)

        EntitlementStore.accept(context, tokens.token(dkt = elsewhere), authoritative = true, serverNowMs = null)

        assertEquals(TokenStatus.NotUsed, EntitlementStore.status(context))
    }
}
