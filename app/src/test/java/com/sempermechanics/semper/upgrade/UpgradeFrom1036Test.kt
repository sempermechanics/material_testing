package com.sempermechanics.semper.upgrade

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.account.AccessStatus
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.account.entitlement.EntitlementStore
import com.sempermechanics.semper.data.account.entitlement.TokenStatus
import com.sempermechanics.semper.data.integrity.RecordIntegrity
import com.sempermechanics.semper.data.integrity.RecordVerifier
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.policy.AppPolicy
import com.sempermechanics.semper.data.policy.VersionVerdict
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.SessionIndexJson
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.runStop
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.gridFrame
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A phone on v1.2-beta.4 (versionCode 1036, 33dce87d) installs this build
 * over itself and keeps its analyses and its settings (readiness TE1-003).
 *
 * The fixtures in `src/test/resources/upgrade/1036/` were not captured from
 * a device: each is what that build's code writes, derived from its source
 * at 33dce87d without running it (`git show 33dce87d:<path>`):
 *
 * - `sessions/index.json`: `SessionStore.write` there encodes
 *   `List<SessionRecord>` with kotlinx `Json { ignoreUnknownKeys = true;
 *   prettyPrint = false }` (defaults, so default-valued fields are left out),
 *   keys in that `SessionRecord`'s declaration order. Four rows: a backed-up
 *   batch, a sweep with a skipped combination, a video, and a run stopped
 *   early with its backup pending. `@FILES@` stands for the app's filesDir.
 * - `shared_prefs/<file>.xml`: Android's preference XML, with the keys and types
 *   `PrefFiles` declared there and the values that build wrote
 *   (`AccessStatus.APPROVED`, consent as `"true"`, mode `licensed`, settings
 *   schema 1).
 *
 * The preference files are put back through an editor, typed as in the XML,
 * because Robolectric caches preferences per process: what is under test is
 * the keys, types and values, not Android's XML reader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpgradeFrom1036Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sessionsRoot get() = File(context.filesDir, "sessions")

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResource("/upgrade/1036/$path")) { path }.readText()

    /** The 1036 index with `@FILES@` made this test's filesDir, as that build wrote absolute paths. */
    private val index1036: String by lazy {
        // JSON-escaped, so a Windows test path's backslashes stay a path.
        val files = JsonPrimitive(context.filesDir.path).toString().removeSurrounding("\"")
        resource("sessions/index.json").replace("@FILES@", files)
    }

    @Before
    fun seed() {
        SessionStore.deleteAll(context)
        sessionsRoot.mkdirs()
        File(sessionsRoot, "index.json").writeText(index1036)
        // One analysis still has its frames on the phone.
        File(sessionsRoot, "batch-1036").apply { mkdirs() }.let { dir ->
            File(dir, "frame_000.dat").writeBytes(gridFrame(0, 4, 4))
        }
        for (name in listOf("semper_session", "semper_remote_config", "dic_settings", "semper_onboarding")) {
            seedPrefs(name, resource("shared_prefs/$name.xml"))
        }
    }

    @After
    fun tearDown() {
        SessionStore.deleteAll(context)
        AccountCache.clear(context)
        AppRemoteConfig.clear(context)
        for (name in listOf("dic_settings", "semper_onboarding")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /** Writes [xml]'s entries into preference file [name], each with the type its element names. */
    private fun seedPrefs(name: String, xml: String) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val key = node.getAttribute("name")
            val value = node.getAttribute("value")
            when (node.tagName) {
                "string" -> editor.putString(key, node.textContent)
                "boolean" -> editor.putBoolean(key, value.toBooleanStrict())
                "int" -> editor.putInt(key, value.toInt())
                "long" -> editor.putLong(key, value.toLong())
                else -> error("unexpected <${node.tagName}> in $name")
            }
        }
        check(editor.commit())
    }

    private fun rows(): Map<String, SessionRecord> = SessionStore.list(context).associateBy { it.id }

    @Test
    fun `every 1036 analysis reads, with what it was`() {
        val rows = rows()

        assertFalse(SessionStore.isIndexCorrupt())
        assertEquals(setOf("batch-1036", "sweep-1036", "video-1036", "stopped-1036"), rows.keys)

        val batch = rows.getValue("batch-1036")
        assertEquals("Tensile A", batch.name)
        assertTrue(batch.renamedByUser && batch.use6x6)
        assertEquals(SessionRecord.SyncState.SYNCED, batch.syncState)
        assertTrue(batch.hasCloudCopy && batch.hasLocalData())
        assertEquals(listOf("def_001.tif", "def_002.tif", "def_003.tif"), batch.frameNames)
        assertEquals(RunStop.Finished, batch.runStop)

        val sweep = rows.getValue("sweep-1036")
        assertTrue(sweep.isSweep)
        assertEquals(listOf("S21·s5", "S31·s7"), sweep.frameNames)
        assertEquals(1, sweep.resolvedSkipNodes().size)
        assertEquals(-12, sweep.resolvedSkipNodes().single().code)
        assertFalse(sweep.lineCutHorizontal)

        val video = rows.getValue("video-1036")
        assertTrue(video.isVideo)
        assertEquals(listOf(0L, 500L, 1000L), video.frameTimesMs)

        val stopped = rows.getValue("stopped-1036")
        assertEquals(RunStop.LowConvergence, stopped.runStop)
        assertTrue(stopped.stoppedEarly)
        assertEquals(6, stopped.plannedFrameCount)
        assertEquals(SessionRecord.SyncState.PENDING, stopped.syncState)
        assertTrue(stopped.metadataStale)
    }

    @Test
    fun `this build writes a 1036 index back byte for byte`() {
        // Every field added since 1036 defaults, and defaults are left out,
        // so the next write keeps the file a 1036 build could still read.
        assertEquals(index1036, SessionIndexJson.encode(SessionIndexJson.decode(index1036)))
    }

    @Test
    fun `a change after the upgrade keeps every other analysis`() {
        val before = rows()

        assertTrue(SessionStore.setSyncState(context, "stopped-1036", SessionRecord.SyncState.SYNCED))

        val after = rows()
        assertEquals(SessionRecord.SyncState.SYNCED, after.getValue("stopped-1036").syncState)
        assertEquals(before - "stopped-1036", after - "stopped-1036")
    }

    @Test
    fun `a 1036 analysis has no integrity record, which is not tampering`() {
        val dir = File(sessionsRoot, "batch-1036")
        assertEquals(RecordIntegrity.NotRecorded, RecordVerifier.verify(dir, localThumbprint = null))
    }

    @Test
    fun `the 1036 account, licence and settings read as they were`() {
        AppSettings.migrate(context)

        assertEquals(AccessStatus.APPROVED, AccountCache.cachedStatus(context))
        assertEquals("u-1036", AccountCache.cachedUid(context))
        assertEquals("user", AccountCache.cachedRole(context))
        assertTrue(AccountCache.isDeviceRegistered(context))
        assertEquals("2026-09-01", AccountCache.termsAcceptedVersion(context))
        assertEquals(true, AccountCache.improvementConsent(context))
        assertTrue(AccountCache.hasAckedBetaNotice(context))

        assertEquals(LicenseEntitlements.MODE_LICENSED, AppRemoteConfig.mode(context))
        assertTrue(AppRemoteConfig.cloudBackupEnabled(context) && AppRemoteConfig.shareEnabled(context))
        assertEquals("institution", AppRemoteConfig.licenseKind(context))
        // Keys added since 1036 are absent: no minimum version, no kill switch.
        assertEquals(0, AppRemoteConfig.minSupportedVersionCode(context))
        assertEquals(emptySet<Any>(), AppRemoteConfig.killSwitches(context))
        assertEquals(VersionVerdict.Current, AppPolicy.version(context))
        // No entitlement record existed at 1036: no token, the cached config decides.
        assertEquals(TokenStatus.NotUsed, EntitlementStore.status(context))

        assertTrue(AppSettings.saveToCloudEnabled(context))
        assertTrue(AppSettings.wifiOnlyUploadEnabled(context))
    }
}
