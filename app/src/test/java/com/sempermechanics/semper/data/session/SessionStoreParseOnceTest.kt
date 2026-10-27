package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [SessionStore] parses `index.json` at most once per change (TD-224): the rows
 * it last parsed or wrote are kept, keyed by the file's length and modified
 * time, so a change, a removal or a lookup does not decode the whole list again,
 * while a file changed behind its back is still read afresh.
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreParseOnceTest {

    @get:Rule
    val clean = CleanAppState()

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun record(id: String, createdAt: Long = 1L) =
        sessionRecord(id = id, createdAt = createdAt, refPath = "ref.png", sessionDir = "/dir/$id")

    private fun indexFile() = File(File(ctx.filesDir, "sessions"), "index.json")

    private fun bakFile() = File(File(ctx.filesDir, "sessions"), "index.json.bak")

    private fun parsesDuring(block: () -> Unit): Int {
        val before = SessionStore.indexParses
        block()
        return SessionStore.indexParses - before
    }

    @Test
    fun `a change to the index this process wrote parses nothing again`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))

        // Each of these parsed the index two or three times.
        assertEquals(0, parsesDuring { assertTrue(SessionStore.update(ctx, "a") { it.copy(cloudSessionId = "c-a") }) })
        assertEquals(0, parsesDuring { SessionStore.forget(ctx, "b") })
        assertEquals(0, parsesDuring { assertEquals("c-a", SessionStore.get(ctx, "a")?.cloudSessionId) })
        assertEquals(listOf("a"), SessionStore.list(ctx).map { it.id })
    }

    @Test
    fun `an index read from disk is parsed once and still promoted to bak`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        val written = indexFile().readText()
        SessionStore.deleteAll(ctx)
        indexFile().writeText(written)

        val parses = parsesDuring {
            assertTrue(SessionStore.upsert(ctx, record("b", 2)))
            assertEquals(setOf("a", "b"), SessionStore.list(ctx).map { it.id }.toSet())
        }

        assertEquals(1, parses)
        assertEquals("the primary the write replaced is the .bak", written, bakFile().readText())
    }

    @Test
    fun `an index changed behind the store's back is read again`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        val onlyA = indexFile().readText()
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))

        indexFile().writeText(onlyA)

        assertNull(SessionStore.get(ctx, "b"))
        assertEquals(listOf("a"), SessionStore.list(ctx).map { it.id })
    }

    @Test
    fun `a truncated primary is not served from what was parsed before`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))
        indexFile().writeText("[")

        // The .bak holds [a], the index before b.
        assertEquals(listOf("a"), SessionStore.list(ctx).map { it.id })
    }
}
