package com.indicvision.semper.cloud

import com.indicvision.semper.data.RestoreDownloadOutcomes
import com.indicvision.semper.data.net.HttpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins which HTTP statuses Range-resume a proxied restore download. */
class RestoreDownloadOutcomesTest {

    @Test
    fun `gateway and Cloud Run kills are transient`() {
        assertTrue(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.INTERNAL_ERROR))
        assertTrue(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.BAD_GATEWAY))
        assertTrue(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.SERVICE_UNAVAILABLE))
        assertTrue(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.GATEWAY_TIMEOUT))
    }

    @Test
    fun `auth and missing-file errors are not transient`() {
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.FORBIDDEN))
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.NOT_FOUND))
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.CONFLICT))
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.BAD_REQUEST))
        assertFalse(RestoreDownloadOutcomes.isTransientProxyFailure(HttpStatus.OK))
    }

    @Test
    fun `resume stops after max attempts`() {
        assertTrue(
            RestoreDownloadOutcomes.shouldResumeAfterHttp(
                HttpStatus.INTERNAL_ERROR,
                attempt = 1,
                maxAttempts = 5,
            ),
        )
        assertFalse(
            RestoreDownloadOutcomes.shouldResumeAfterHttp(
                HttpStatus.INTERNAL_ERROR,
                attempt = 5,
                maxAttempts = 5,
            ),
        )
        assertFalse(
            RestoreDownloadOutcomes.shouldResumeAfterHttp(
                HttpStatus.NOT_FOUND,
                attempt = 1,
                maxAttempts = 5,
            ),
        )
    }

    @Test
    fun `parseContentRangeTotal reads the object size`() {
        assertEquals(
            84158403L,
            RestoreDownloadOutcomes.parseContentRangeTotal("bytes 0-1048575/84158403"),
        )
        assertEquals(
            100L,
            RestoreDownloadOutcomes.parseContentRangeTotal("bytes 50-99/100"),
        )
        assertEquals(null, RestoreDownloadOutcomes.parseContentRangeTotal("bytes 0-10/*"))
        assertEquals(null, RestoreDownloadOutcomes.parseContentRangeTotal(null))
        assertEquals(null, RestoreDownloadOutcomes.parseContentRangeTotal(""))
    }
}
