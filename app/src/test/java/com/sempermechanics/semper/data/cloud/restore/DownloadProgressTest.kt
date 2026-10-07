package com.sempermechanics.semper.data.cloud.restore

import com.sempermechanics.semper.navigation.IntentKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `percent survives a bundle past 2 GB and an unknown size`() {
        val threeGb = 3L * 1024 * 1024 * 1024
        assertEquals(50, DownloadProgress.percent(threeGb / 2, threeGb))
        assertEquals(0, DownloadProgress.percent(123L, 0L))
        assertEquals(100, DownloadProgress.percent(threeGb + 1, threeGb))
    }

    @Test
    fun `restore progress names its row and the bundle download does not`() {
        val restore = DownloadProgress.data(done = 1, total = 4, localId = "s1")
        assertEquals("s1", restore.getString(IntentKeys.SESSION_LOCAL_ID))
        assertEquals(IntentKeys.PHASE_DOWNLOAD, restore.getString(IntentKeys.UPLOAD_PHASE))
        assertEquals(25, restore.getInt(IntentKeys.UPLOAD_PERCENT, -1))

        val bundle = DownloadProgress.data(done = 1, total = 4)
        assertFalse(bundle.keyValueMap.containsKey(IntentKeys.SESSION_LOCAL_ID))
    }
}
