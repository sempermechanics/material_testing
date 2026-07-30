package com.indicvision.semper.session

import com.indicvision.semper.data.SessionPaths
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPathsTest {

    @Test
    fun `RAW_DEFORMED_SUBDIR equals raw_deformed`() {
        assertEquals("raw_deformed", SessionPaths.RAW_DEFORMED_SUBDIR)
    }
}
