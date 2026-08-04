package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.util.Digests
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Drive resumable-upload state machine in [IndicApi.uploadResumable],
 * exercised against a fake Drive (MockWebServer).
 *
 * Every case here encodes a bug this project actually shipped once:
 * resuming re-sent bytes Drive already had (size-mismatch 400s), an upload
 * that was already complete "finished without a final Drive response", and
 * the server-supplied chunk size being trusted blindly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadResumableTest {

    private lateinit var server: MockWebServer
    private lateinit var api: IndicApi
    private lateinit var file: File

    private val chunk256k = 256 * 1024

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = IndicApi.get(ApplicationProvider.getApplicationContext<Context>())
        file = File.createTempFile("upload", ".bin")
    }

    @After
    fun tearDown() {
        server.close()
        file.delete()
    }

    private fun writeBytes(n: Int) = file.writeBytes(ByteArray(n) { (it % 251).toByte() })

    private fun enqueue(code: Int, headers: Headers = Headers.headersOf(), body: String = "") {
        server.enqueue(MockResponse(code = code, headers = headers, body = body))
    }

    @Test
    fun `resumes from the offset Drive reports instead of resending`() = runBlocking {
        writeBytes(1000)
        // Probe: Drive already holds bytes 0-399.
        enqueue(308, Headers.headersOf("Range", "bytes=0-399"))
        // The continuation PUT completes the file — no md5Checksum (Drive v3 default).
        enqueue(200, body = """{"id":"drv1"}""")

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k)

        assertEquals("drv1", driveId)
        assertEquals(Digests.md5Hex(file), md5)
        // Request 1 = probe; request 2 must continue at byte 400, not 0.
        assertEquals("bytes */1000", server.takeRequest().headers["Content-Range"])
        val put = server.takeRequest()
        assertEquals("bytes 400-999/1000", put.headers["Content-Range"])
        assertEquals(600L, put.body?.size?.toLong() ?: 0L)
    }

    @Test
    fun `already-complete upload returns local md5 when Drive omits md5Checksum`() = runBlocking {
        writeBytes(500)
        enqueue(200, body = """{"id":"done1"}""")

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k)

        assertEquals("done1", driveId)
        assertEquals(Digests.md5Hex(file), md5)
        // No bytes may be re-sent: the probe must be the only request.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `already-complete upload prefers Drive md5Checksum when present`() = runBlocking {
        writeBytes(500)
        enqueue(200, body = """{"id":"done2","md5Checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""")

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k)

        assertEquals("done2", driveId)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", md5)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `fresh upload with no Range header starts at zero and chunks correctly`() = runBlocking {
        val total = chunk256k + 1000 // forces exactly two chunks at the min chunk size
        writeBytes(total)
        enqueue(308) // probe: nothing received yet
        enqueue(308, Headers.headersOf("Range", "bytes=0-${chunk256k - 1}"))
        enqueue(200, body = """{"id":"drv2"}""")

        // chunkSize=1 is below Drive's 256 KiB minimum — the clamp must raise it.
        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, 1)

        assertEquals("drv2", driveId)
        assertNotNull(md5)
        assertEquals(Digests.md5Hex(file), md5)
        server.takeRequest() // probe
        assertEquals("bytes 0-${chunk256k - 1}/$total", server.takeRequest().headers["Content-Range"])
        assertEquals("bytes $chunk256k-${total - 1}/$total", server.takeRequest().headers["Content-Range"])
    }
}
