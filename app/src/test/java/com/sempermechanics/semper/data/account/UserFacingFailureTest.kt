package com.sempermechanics.semper.data.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.sempermechanics.semper.R
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.cloud.CloudReconcile
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.restore.CloudRestore
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.HttpFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Failures reach users as the app's own strings, never an exception's or the
 * server's text (readiness AE1-009): offline, a cloud that is not answering
 * and a lapsed session by name, anything else as the caller's fallback.
 */
@RunWith(RobolectricTestRunner::class)
class UserFacingFailureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun text(id: Int) = context.getString(id)

    private fun firebaseFailing(e: Exception): String = runBlocking {
        firebaseOp<Unit>(context, "test", R.string.auth_reset_failed) { throw e }
    }.exceptionOrNull()!!.message!!

    @Test
    fun `a Firebase failure shows the caller's text, not Firebase's`() {
        val raw = "An internal error has occurred. [ CONFIGURATION_NOT_FOUND ]"
        assertEquals(text(R.string.auth_reset_failed), firebaseFailing(IllegalStateException(raw)))
    }

    @Test
    fun `no network and too many attempts are named`() {
        assertEquals(text(R.string.error_offline), firebaseFailing(FirebaseNetworkException("A network error")))
        assertEquals(
            text(R.string.auth_too_many_requests),
            firebaseFailing(FirebaseTooManyRequestsException("We have blocked all requests")),
        )
    }

    @Test
    fun `a failure's text is shown only when it is the app's own`() {
        assertEquals("Fallback", IOException("ECONNRESET").userMessageOr("Fallback"))
        assertEquals("Ours", UserMessageException("Ours").userMessageOr("Fallback"))
    }

    @Test
    fun `an HTTP failure maps by kind`() {
        fun of(e: Throwable) = HttpFailure.classify(e).userMessage(R.string.error_generic)
        assertEquals(R.string.error_offline, of(IOException("timeout")))
        assertEquals(R.string.error_cloud_unavailable, of(ApiException(503, "{}")))
        assertEquals(R.string.error_cloud_unavailable, of(ApiException(429, "")))
        assertEquals(R.string.error_session_expired, of(ApiException(401, """{"detail":"bad audience"}""")))
        assertEquals(R.string.error_generic, of(ApiException(400, """{"detail":"weird"}""")))
    }

    @Test
    fun `the backups list says offline or not answering, not the exception`() = runBlocking {
        val api = FakeCloudApi()
        val tokens = FakeTokens()

        api.onListSessions = { _, _ -> throw IOException("Unable to resolve host \"api.example\"") }
        assertEquals(
            CloudRestore.ListOutcome.Failed(R.string.restore_list_offline),
            CloudRestore.listCompleted(context, api, tokens),
        )
        api.onListSessions = { _, _ -> throw ApiException(503, """{"detail":"firestore_unreachable"}""") }
        assertEquals(
            CloudRestore.ListOutcome.Failed(R.string.error_cloud_unavailable),
            CloudRestore.listCompleted(context, api, tokens),
        )
    }

    @Test
    fun `a failed cloud check names no HTTP status`() {
        val failed = CloudReconcile.reconcileFailure(HttpFailure.classify(ApiException(503, "{}")))
        assertEquals(CloudSync.Outcome.Failed(R.string.cloud_check_server), failed)
        assertFalse(text(R.string.cloud_check_server).contains("HTTP"))
    }

    @Test
    fun `an unknown restore refusal is the generic message, not its code`() {
        assertEquals(
            text(R.string.restore_failed_generic),
            LicenseErrors.restoreMessage(context, """{"detail":"quota_shard_hot"}"""),
        )
    }
}
