package com.rafad.indicvisiondic.cloud

import com.rafad.indicvisiondic.data.CloudRestore
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudRestoreMappingTest {

    @Test
    fun `KEY_CLOUD_SESSION_ID equals CLOUD_SESSION_ID`() {
        assertEquals("CLOUD_SESSION_ID", CloudRestore.KEY_CLOUD_SESSION_ID)
    }
}
