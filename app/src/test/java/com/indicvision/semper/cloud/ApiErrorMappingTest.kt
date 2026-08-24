package com.indicvision.semper.cloud

import com.indicvision.semper.data.UploadWorkOutcomes
import com.indicvision.semper.data.net.ApiErrors
import com.indicvision.semper.data.net.IndicApiHttp
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a backend failure is read on the client: which code it *is* (not which
 * code it mentions), and the correlation id that joins it to the backend log.
 *
 * Robolectric because [ApiErrors] parses the body with `org.json`, which is a
 * stub on the plain JVM classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiErrorMappingTest {

    @Test
    fun `detail is read from the FastAPI envelope`() {
        assertEquals(
            ApiErrors.DEVICE_NOT_ACTIVE,
            ApiErrors.detailOf("""{"detail":"device_not_active"}"""),
        )
        assertTrue(
            ApiErrors.hasCode("""{"detail":"device_not_active"}""", ApiErrors.DEVICE_NOT_ACTIVE),
        )
    }

    @Test
    fun `a code quoted inside a message is not that code`() {
        // The substring match this replaced turned any 409 whose text mentioned
        // a device conflict into DeviceConflictException, which sends the user
        // to "back up from your other device" for an unrelated rejection.
        val body = """{"detail":"session_quota_exceeded: 5/5 stored. Not a device_conflict."}"""
        assertFalse(ApiErrors.hasCode(body, ApiErrors.DEVICE_CONFLICT))
        assertTrue(ApiErrors.hasCode(body, ApiErrors.SESSION_QUOTA_EXCEEDED))
    }

    @Test
    fun `a non-JSON body is not mistaken for a code`() {
        // API Gateway and Cloud Run deadline kills answer with plain text, or
        // with nothing at all.
        assertEquals("", ApiErrors.detailOf(""))
        assertEquals("upstream request timeout", ApiErrors.detailOf("upstream request timeout"))
        assertFalse(ApiErrors.hasCode("", ApiErrors.SESSION_NOT_FOUND))
        assertFalse(ApiErrors.hasCode("<html>502 Bad Gateway</html>", ApiErrors.RATE_LIMITED))
    }

    @Test
    fun `malformed JSON falls back to the raw body`() {
        assertEquals("""{"detail":""", ApiErrors.detailOf("""{"detail":"""))
    }

    @Test
    fun `request id is taken from the response header when present`() {
        assertEquals("a1b2c3d4e5f6", IndicApiHttp.requestIdOf(response("a1b2c3d4e5f6")))
        assertNull(IndicApiHttp.requestIdOf(response(null)))
        assertNull(IndicApiHttp.requestIdOf(response("   ")))
    }

    @Test
    fun `failure reasons carry the reference only when there is one`() {
        assertEquals(
            "Backup failed (ref: a1b2c3d4e5f6)",
            UploadWorkOutcomes.withRef("Backup failed", "a1b2c3d4e5f6"),
        )
        assertEquals("Backup failed", UploadWorkOutcomes.withRef("Backup failed", null))
        assertEquals("Backup failed", UploadWorkOutcomes.withRef("Backup failed", ""))
    }

    private fun response(requestId: String?): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.invalid/v1/sessions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_CONFLICT)
            .message("Conflict")
            .apply { requestId?.let { header("X-Request-Id", it) } }
            .build()

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}
