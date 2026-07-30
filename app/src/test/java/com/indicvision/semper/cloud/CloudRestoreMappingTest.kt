package com.indicvision.semper.cloud

import com.indicvision.semper.data.CloudRestore
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudRestoreMappingTest {

    @Test
    fun `KEY_CLOUD_SESSION_ID equals CLOUD_SESSION_ID`() {
        assertEquals("CLOUD_SESSION_ID", CloudRestore.KEY_CLOUD_SESSION_ID)
    }
}
