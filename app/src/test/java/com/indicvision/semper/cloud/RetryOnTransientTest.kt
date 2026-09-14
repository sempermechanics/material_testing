package com.indicvision.semper.cloud

import com.indicvision.semper.data.net.HttpStatus
import com.indicvision.semper.data.net.RetryOnTransient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [RetryOnTransient] against a fake backend.
 *
 * The distinction under test is not "retry on failure" but *which* failure:
 * 429 means the throttle rejected the call before the handler ran, so nothing
 * happened and a repeat is free. 503 carries no such promise, so it is retried
 * only where a duplicate is harmless. Getting that wrong duplicates a
 * server-side effect nobody would see.
 */
class RetryOnTransientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val json = "application/json; charset=utf-8".toMediaType()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().addInterceptor(RetryOnTransient()).build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun get(path: String = "/v1/config") =
        client.newCall(Request.Builder().url(server.url(path)).get().build()).execute()

    private fun post(path: String) = client.newCall(
        Request.Builder().url(server.url(path)).post("{}".toRequestBody(json)).build(),
    ).execute()

    private fun throttled(retryAfter: String? = null) = MockResponse(
        code = HttpStatus.TOO_MANY_REQUESTS,
        headers = retryAfter?.let { Headers.headersOf("Retry-After", it) } ?: Headers.headersOf(),
        body = """{"detail":"rate_limited"}""",
    )

    private fun ok() = MockResponse(code = HttpStatus.OK, body = """{"ok":true}""")

    private fun unavailable() = MockResponse(code = HttpStatus.SERVICE_UNAVAILABLE, body = "")

    // ------------------------------------------------------------------- 429

    @Test
    fun `a throttled call is retried and the caller sees the success`() {
        server.enqueue(throttled())
        server.enqueue(ok())

        get().use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries are capped so a throttled backend is not hammered`() {
        repeat(5) { server.enqueue(throttled()) }

        get().use { assertEquals(HttpStatus.TOO_MANY_REQUESTS, it.code) }

        // Three attempts in total, not five: the cap is the point.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a throttled POST is retried too — nothing ran on the first attempt`() {
        server.enqueue(throttled())
        server.enqueue(ok())

        post("/v1/sessions").use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Retry-After is honoured over the default backoff`() {
        server.enqueue(throttled(retryAfter = "1"))
        server.enqueue(ok())

        val started = System.nanoTime()
        get().use { assertEquals(HttpStatus.OK, it.code) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // The default first step is 500ms, so anything at or past a second can
        // only have come from the header.
        assertTrue("waited ${elapsedMs}ms", elapsedMs >= 950)
    }

    // ------------------------------------------------------------------- 503

    @Test
    fun `an unavailable GET is retried`() {
        server.enqueue(unavailable())
        server.enqueue(ok())

        get().use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an unavailable POST that is not idempotent is left alone`() {
        server.enqueue(unavailable())
        server.enqueue(ok())

        // Creating a session twice costs a Drive object, so a 503 that may have
        // been delivered is surfaced rather than repeated.
        post("/v1/sessions").use { assertEquals(HttpStatus.SERVICE_UNAVAILABLE, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an unavailable seat checkout is retried — re-calling it is the heartbeat`() {
        server.enqueue(unavailable())
        server.enqueue(ok())

        post("/v1/licenses/checkout").use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    // ------------------------------------------------------ everything else

    @Test
    fun `a server error is not a transient answer`() {
        server.enqueue(MockResponse(code = HttpStatus.INTERNAL_ERROR, body = ""))
        server.enqueue(ok())

        get().use { assertEquals(HttpStatus.INTERNAL_ERROR, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a refusal is not retried`() {
        server.enqueue(MockResponse(code = HttpStatus.FORBIDDEN, body = """{"detail":"not_approved"}"""))
        server.enqueue(ok())

        get().use { assertEquals(HttpStatus.FORBIDDEN, it.code) }

        assertEquals(1, server.requestCount)
    }
}
