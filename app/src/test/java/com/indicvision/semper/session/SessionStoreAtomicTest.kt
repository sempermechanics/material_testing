package com.indicvision.semper.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.net.TokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A truncated [SessionStore] index must never be treated as empty and overwritten
 * with a one-record file — that was silent total loss of session metadata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStoreAtomicTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        TokenStore.clear(ctx)
        SessionStore.deleteAll(ctx)
    }

    @After
    fun tearDown() {
        SessionStore.deleteAll(ctx)
        TokenStore.clear(ctx)
    }

    private fun record(id: String, createdAt: Long = 1L) = SessionRecord(
        id = id,
        name = id,
        createdAt = createdAt,
        updatedAt = createdAt,
        frameCount = 1,
        subset = 41,
        step = 5,
        strainWindow = 15,
        imgW = 100,
        imgH = 100,
        roiX = 0,
        roiY = 0,
        roiW = 100,
        roiH = 100,
        refPath = "ref.png",
        refName = "ref.png",
        sessionDir = "/dir/$id",
    )

    private fun indexFile(): File =
        File(File(ctx.filesDir, "sessions"), "index.json")

    private fun bakFile(): File =
        File(File(ctx.filesDir, "sessions"), "index.json.bak")

    @Test
    fun `truncated index is not overwritten and prior sessions survive via bak`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))
        // Third write promotes [a,b] into .bak (bak always holds the prior good file).
        assertTrue(SessionStore.upsert(ctx, record("c", 3)))
        assertEquals(3, SessionStore.list(ctx).size)

        // Crash mid-write: leave a truncated primary; bak still has a+b.
        indexFile().writeText("[{\"id\":\"a\"")
        val listed = SessionStore.list(ctx)
        assertEquals(setOf("a", "b"), listed.map { it.id }.toSet())
        assertFalse(SessionStore.isIndexCorrupt())

        // Both primary and bak unreadable → refuse mutations (no one-record clobber).
        indexFile().writeText("{truncated")
        bakFile().writeText("{also-bad")

        assertEquals(emptyList<SessionRecord>(), SessionStore.list(ctx))
        assertTrue(SessionStore.isIndexCorrupt())
        assertFalse(
            "corrupt index must not be replaced with a single new record",
            SessionStore.upsert(ctx, record("d", 4)),
        )
        assertTrue(indexFile().readText().startsWith("{truncated"))
    }

    @Test
    fun `list restores from bak when primary is truncated`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))
        assertTrue(SessionStore.upsert(ctx, record("c", 3)))
        indexFile().writeText("[")

        val listed = SessionStore.list(ctx)
        assertEquals(setOf("a", "b"), listed.map { it.id }.toSet())
        assertFalse(SessionStore.isIndexCorrupt())
        assertEquals(2, SessionStore.list(ctx).size)
    }

    @Test
    fun `atomic write leaves a bak of the prior good index`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        val afterFirst = indexFile().readText()
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))
        assertTrue(bakFile().exists())
        assertEquals(afterFirst, bakFile().readText())
    }
}
