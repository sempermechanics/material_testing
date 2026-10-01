package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A cloud erase whose caller goes away is cancelled, not "cloud unreachable":
 * reporting it as a failure logs a non-fatal and tells a screen that no longer
 * exists that the backend could not be reached.
 */
@RunWith(RobolectricTestRunner::class)
class EraseCancellationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi().apply {
        onDeleteSession = { _, _ -> throw CancellationException("screen closed") }
    }
    private val tokens = FakeTokens()

    @Before
    fun setUp() {
        SessionStore.deleteAll(context)
    }

    @After
    fun tearDown() {
        SessionStore.deleteAll(context)
    }

    @Test
    fun `a cancelled erase everywhere is cancelled and keeps the local copy`() {
        assertTrue(SessionStore.upsert(context, record("s1", cloudId = "c1"), allowOverLimit = true))

        assertThrows(CancellationException::class.java) {
            runBlocking { CloudSync.eraseEverywhere(context, "s1", api, tokens) }
        }
        assertNotNull(SessionStore.get(context, "s1"))
    }

    @Test
    fun `a cancelled cloud backup delete is cancelled`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { CloudSync.eraseCloudBackup(context, "c1", "s1", api, tokens) }
        }
    }

    private fun record(id: String, cloudId: String) = SessionRecord(
        id = id, name = id, createdAt = 1L, updatedAt = 1L, frameCount = 1,
        subset = 41, step = 5, strainWindow = 15,
        imgW = 100, imgH = 100, roiX = 0, roiY = 0, roiW = 100, roiH = 100,
        refPath = "", refName = "ref.png", sessionDir = SessionStore.dirFor(context, id).absolutePath,
        cloudSessionId = cloudId,
        syncState = SessionRecord.SyncState.SYNCED,
    )
}
