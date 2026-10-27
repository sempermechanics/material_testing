@file:Suppress("MagicNumber")

package com.sempermechanics.semper.data.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.sempermechanics.semper.data.net.ArtifactRoles
import com.sempermechanics.semper.data.net.FileCompleteRequest
import com.sempermechanics.semper.data.net.FileSpecDto
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.SessionCreateRequest
import com.sempermechanics.semper.data.net.UploadTargetDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * One signed-in round trip against a live backend (TD-248): sign in, `GET
 * /v1/config`, register this emulator's key, back up one small file (create,
 * upload to Drive, complete), see it listed, then delete the analysis.
 *
 * It runs only when given all three of `STAGING_API_BASE_URL`,
 * `STAGING_TEST_EMAIL` and `STAGING_TEST_PASSWORD`, and skips otherwise (a local
 * run, a fork's PR). The account must be a staff CI test account (ADR-027): an
 * ordinary login is refused `not_approved`, and only a CI account gives up its
 * oldest phone when a fresh emulator registers past its `maxDevices`. The app
 * under test keeps its emulator bypass ([com.sempermechanics.semper.data.account.DevAuth]),
 * so the rest of the suite still boots local-only; this test calls the client
 * directly with the staging URL.
 */
@RunWith(AndroidJUnit4::class)
class StagingRoundTripDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val auth = FirebaseAuth.getInstance()
    private lateinit var api: SemperApi
    private lateinit var email: String
    private lateinit var password: String
    private var file: File? = null

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("STAGING_API_BASE_URL", "")
        email = args.getString("STAGING_TEST_EMAIL", "")
        password = args.getString("STAGING_TEST_PASSWORD", "")
        assumeTrue(
            "Skipped — no staging round trip (STAGING_API_BASE_URL / STAGING_TEST_EMAIL / STAGING_TEST_PASSWORD)",
            baseUrl.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
        )
        api = SemperApi(context, baseUrl, null)
    }

    /** The rest of the suite runs on this emulator after it: leave no one signed in. */
    @After
    fun tearDown() {
        auth.signOut()
        file?.delete()
    }

    @Test
    fun aCiTestAccountBacksUpOneFileAndDeletesIt() = runBlocking {
        val user = auth.signInWithEmailAndPassword(email, password).await().user
        assertNotNull(user)
        val token = user!!.getIdToken(true).await().token!!

        val config = api.getConfig(token)
        assertEquals("ci", config.testAccount?.purpose)
        api.registerDevice(token)

        val bytes = "TD-248 round trip ${UUID.randomUUID()}\n".toByteArray()
        val upload = File(context.cacheDir, "td248-${System.currentTimeMillis()}.txt").also {
            it.writeBytes(bytes)
            file = it
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val created = api.createSession(
            token,
            SessionCreateRequest(
                specimen = "TD-248 round trip",
                files = listOf(FileSpecDto(upload.name, ArtifactRoles.EXTRAS, bytes.size.toLong(), sha256)),
                localSessionId = "td248-${UUID.randomUUID()}",
            ),
        )
        val sessionId = created.sessionId
        try {
            val target = created.uploads.singleOrNull() ?: awaitTarget(token, sessionId)
            val (driveFileId, md5) = api.uploadResumable(target.uploadUrl, upload, target.chunkSize)
            api.completeFile(
                token,
                target.fileId,
                FileCompleteRequest(sessionId, driveFileId, bytes.size.toLong(), md5),
            )
            val listed = api.listSessionFiles(token, sessionId).files
            assertTrue("$listed", listed.any { it.name == upload.name })
        } finally {
            deleteWhenProvisioned(token, sessionId)
        }
    }

    /** The backend opens the Drive upload in a Cloud Task: poll for it. */
    private suspend fun awaitTarget(token: String, sessionId: String): UploadTargetDto {
        var wait = 500L
        repeat(12) {
            delay(wait)
            val pending = api.listSessionUploads(token, sessionId)
            assertTrue("provisioning failed: ${pending.provisionError}", pending.status != "PROVISION_FAILED")
            pending.uploads.singleOrNull()?.let { return UploadTargetDto(it.fileId, it.uploadUrl, it.chunkSize) }
            wait = (wait * 2).coerceAtMost(5_000L)
        }
        throw AssertionError("session $sessionId still provisioning")
    }

    /** A delete answers 409 while the session is provisioning; retry a little. */
    private suspend fun deleteWhenProvisioned(token: String, sessionId: String) {
        var last: Exception? = null
        repeat(6) {
            try {
                api.deleteSession(token, sessionId)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(2_000L)
            }
        }
        throw AssertionError("could not delete $sessionId", last)
    }
}
