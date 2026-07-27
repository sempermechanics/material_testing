package com.rafad.indicvisiondic.cloud

import com.rafad.indicvisiondic.data.SessionUploadBundler
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionUploadBundlerTest {

    @Test
    fun `BundleCounts holds correct values`() {
        val counts = SessionUploadBundler.BundleCounts(reports = 3, processed = 7)
        assertEquals(3, counts.reports)
        assertEquals(7, counts.processed)
    }
}
