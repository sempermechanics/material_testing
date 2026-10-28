package com.sempermechanics.semper.data.account.entitlement

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.ClientNonce
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.privatePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole licensing chain a release runs once it pins a signing key
 * (readiness TE4-101): a `/v1/config` answer carrying a signed token
 * ([AppRemoteConfig.apply], authoritative) → [EntitlementVerifier] against
 * this install's real device key → [EntitlementStore]'s Keystore seal and
 * clock → [LicenseEntitlements]. The cached config always says licensed, with
 * share, backup and 500 analyses; the token may only narrow that.
 *
 * Test keys and pins only ([TestEntitlementTokens]); the config cache and the
 * token are put back as they were after each test.
 */
@RunWith(AndroidJUnit4::class)
class LicenseEntitlementsTokenDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val tokens = TestEntitlementTokens(context)
    private val configPrefs = privatePrefs(context, PrefFiles.RemoteConfig.NAME)
    private var savedConfig: Map<String, *> = emptyMap<String, Any>()

    @Before
    fun pinTestKey() {
        savedConfig = configPrefs.all.toMap()
        EntitlementStore.testPins = tokens.pins
        EntitlementStore.clear(context)
        // The server's clock as the API's Date header would teach it: now.
        ClientNonce.observeServerTime(System.currentTimeMillis())
    }

    @After
    fun restore() {
        EntitlementStore.clear(context)
        EntitlementStore.testPins = null
        ClientNonce.reset()
        val edit = configPrefs.edit().clear()
        savedConfig.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(edit.commit())
    }

    /** The backend's `/v1/config` answer: licensed with everything, carrying [token]. */
    private fun answer(token: String) = AppRemoteConfig.apply(
        context,
        AppConfigDto(
            maxSessions = SERVER_MAX,
            mode = LicenseEntitlements.MODE_LICENSED,
            cloudBackupEnabled = true,
            shareEnabled = true,
            entitlement = token,
        ),
        authoritative = true,
    )

    private fun status() = EntitlementStore.status(context)

    /** Demo's limits, though the server's answer still says licensed. */
    private fun assertNarrowedToDemo(why: GraceEnd) {
        assertEquals(TokenStatus.Lapsed(why.name), status())
        assertTrue("the server's answer is unchanged", LicenseEntitlements.serverSaysLicensed(context))
        assertEquals(LicenseEntitlements.MODE_DEMO, LicenseEntitlements.mode(context))
        assertFalse(LicenseEntitlements.isLicensed(context))
        assertFalse("share", LicenseEntitlements.shareLicensed(context))
        assertFalse("backup", LicenseEntitlements.backupLicensed(context))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(context))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.shownAnalysisCap(context))
    }

    @Test
    fun aValidLicensedTokenKeepsWhatTheServerAllows() {
        answer(tokens.token())

        assertTrue("status ${status()}", status() is TokenStatus.Valid)
        assertEquals(LicenseEntitlements.MODE_LICENSED, LicenseEntitlements.mode(context))
        assertTrue(LicenseEntitlements.shareLicensed(context))
        assertTrue(LicenseEntitlements.backupLicensed(context))
        assertEquals(SERVER_MAX, LicenseEntitlements.analysisCap(context))
    }

    @Test
    fun aValidTokenNarrowsShareBackupAndTheCap() {
        answer(tokens.token(share = false, backup = false, maxAnalyses = TOKEN_MAX))

        assertTrue("status ${status()}", status() is TokenStatus.Valid)
        assertTrue("still licensed", LicenseEntitlements.isLicensed(context))
        assertFalse("share", LicenseEntitlements.shareLicensed(context))
        assertFalse("backup", LicenseEntitlements.backupLicensed(context))
        assertEquals(TOKEN_MAX, LicenseEntitlements.analysisCap(context))
        assertEquals(TOKEN_MAX, LicenseEntitlements.shownAnalysisCap(context))
    }

    @Test
    fun aValidDemoTokenNarrowsALicensedAnswerToDemo() {
        answer(tokens.token(mode = LicenseEntitlements.MODE_DEMO, maxAnalyses = 0))

        assertTrue("status ${status()}", status() is TokenStatus.Valid)
        assertEquals(LicenseEntitlements.MODE_DEMO, LicenseEntitlements.mode(context))
        assertFalse("share", LicenseEntitlements.shareLicensed(context))
        assertFalse("backup", LicenseEntitlements.backupLicensed(context))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(context))
    }

    @Test
    fun aTokenPastItsFourteenDayGraceLapsesToDemo() {
        // Issued 15 days ago: its 14-day offline grace ended yesterday; the licence itself runs on.
        val iat = nowS() - 15 * TestEntitlementTokens.DAY_S
        answer(tokens.token(iat = iat, exp = nowS() + YEAR_S))

        assertNarrowedToDemo(GraceEnd.GRACE_OVER)
    }

    @Test
    fun aTokenPastItsExpiryLapsesToDemo() {
        // Issued 15 days ago and expired yesterday, at the end of its grace.
        val iat = nowS() - 15 * TestEntitlementTokens.DAY_S
        answer(tokens.token(iat = iat, exp = iat + TestEntitlementTokens.GRACE_S))

        assertNarrowedToDemo(GraceEnd.EXPIRED)
    }

    @Test
    fun anEditedSealedRecordLapsesToDemo() {
        answer(tokens.token())
        val sealed = checkNotNull(configSealed()) { "nothing sealed" }
        val i = sealed.length / 2
        val edited = sealed.substring(0, i) + (if (sealed[i] == 'A') 'B' else 'A') + sealed.substring(i + 1)
        assertTrue(
            privatePrefs(context, PrefFiles.Entitlement.NAME).edit()
                .putString(PrefFiles.Entitlement.SEALED.name, edited).commit(),
        )
        // A new process reads the record from disk again.
        EntitlementStore.testPins = tokens.pins

        assertNarrowedToDemo(GraceEnd.TAMPERED)
    }

    private fun configSealed(): String? =
        privatePrefs(context, PrefFiles.Entitlement.NAME).getString(PrefFiles.Entitlement.SEALED.name, null)

    private fun nowS() = System.currentTimeMillis() / 1000

    private companion object {
        const val SERVER_MAX = 500
        const val TOKEN_MAX = 100
        const val YEAR_S = 365 * TestEntitlementTokens.DAY_S
    }
}
