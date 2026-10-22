package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.ext.AppExtensions
import com.sempermechanics.semper.ext.SessionExtension
import com.sempermechanics.semper.fixtures.CleanAppState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [SessionRecord.extras] and the legacy-key reader (ADR-025, TD-298). A
 * 2026-10 Material Testing `index.json` keeps eight lab fields at the top level
 * of each row; with an extension naming them, they move under `extras` and
 * survive a write, with the same meaning. Semper, with no extension, reads and
 * writes its index byte for byte as before.
 */
@RunWith(RobolectricTestRunner::class)
class SessionIndexExtrasTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The fork's eight top-level record fields (mt:data/session/SessionRecord.kt). */
    private object LabExtension : SessionExtension {
        override val legacyKeys = setOf(
            "testType",
            "crossSectionMm2",
            "loadAxisX",
            "geometry",
            "curveCorrection",
            "loadsN",
            "loadSource",
            "loadMapping",
        )
    }

    @After
    fun resetExtensions() = AppExtensions.resetForTest()

    private fun indexFile() = File(context.filesDir, "sessions/index.json")

    private fun resource(name: String): String = checkNotNull(javaClass.classLoader)
        .getResource(name)
        .readText(Charsets.UTF_8)
        .trimEnd('\n')

    /** Puts [text] on disk as the index, reads it, and has the store write it back. */
    private fun readAndRewrite(text: String): String {
        indexFile().parentFile?.mkdirs()
        indexFile().writeText(text, Charsets.UTF_8)
        SessionStore.list(context)
        // A transform that changes nothing still rewrites the whole index.
        assertTrue(SessionStore.update(context, "no-such-row") { it })
        return indexFile().readText(Charsets.UTF_8)
    }

    private fun rowsOf(text: String): List<JsonObject> = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }

    @Test
    fun `Semper's own index reads and writes back byte for byte`() {
        val golden = resource(GOLDEN)

        assertEquals(golden, readAndRewrite(golden))
    }

    @Test
    fun `with an extension installed, an index without its keys still writes back byte for byte`() {
        AppExtensions.install(session = LabExtension)
        val golden = resource(GOLDEN)

        assertEquals(golden, readAndRewrite(golden))
    }

    @Test
    fun `a fork row's lab fields move under extras and keep their meaning across a write`() {
        AppExtensions.install(session = LabExtension)
        val original = resource(FORK)

        val written = readAndRewrite(original)

        val before = rowsOf(original)
        val after = rowsOf(written).associateBy { it.getValue("id") }
        assertEquals(before.size, after.size)
        for (row in before) {
            val rewritten = after.getValue(row.getValue("id"))
            assertFalse(rewritten.keys.any { it in LabExtension.legacyKeys })
            val extras = rewritten[SessionIndexJson.EXTRAS_KEY]?.jsonObject ?: JsonObject(emptyMap())
            assertEquals(row.filterKeys { it in LabExtension.legacyKeys }, extras.toMap())
            // Put back at the top level, the row is the one the fork wrote.
            assertEquals(row, JsonObject(rewritten - SessionIndexJson.EXTRAS_KEY + extras))
        }
    }

    @Test
    fun `a fork index read twice through the extension gives equal records`() {
        AppExtensions.install(session = LabExtension)
        val written = readAndRewrite(resource(FORK))

        val first = SessionIndexJson.decode(resource(FORK))
        val second = SessionIndexJson.decode(written)

        assertEquals(first, second)
        val tensile = second.single { it.id == "tensile-1" }
        assertEquals(JsonPrimitive("tensile"), tensile.extras["testType"])
        assertEquals("[0.0,512.25,null,1530.5]", tensile.extras["loadsN"].toString())
        assertTrue(second.single { it.id == "plain-1" }.extras.isEmpty())
    }

    @Test
    fun `without the extension the lab fields are dropped on the next write`() {
        val written = readAndRewrite(resource(FORK))

        assertTrue(rowsOf(written).none { row -> row.keys.any { it in LabExtension.legacyKeys } })
        assertFalse(written.contains(SessionIndexJson.EXTRAS_KEY))
    }

    @Test
    fun `an extras value already present wins over a stray top-level key`() {
        val row = Json.parseToJsonElement("""{"id":"a","testType":"bending","extras":{"testType":"tensile","x":1}}""")

        val lifted = SessionIndexJson.liftLegacyKeys(row, setOf("testType")).jsonObject

        assertEquals(setOf("id", "extras"), lifted.keys)
        assertEquals(JsonPrimitive("tensile"), lifted.getValue("extras").jsonObject["testType"])
        assertEquals(JsonPrimitive(1), lifted.getValue("extras").jsonObject["x"])
    }

    @Test
    fun `a row with none of the keys, or not an object, is left as it is`() {
        val plain = Json.parseToJsonElement("""{"id":"a"}""")
        val notAnObject = JsonArray(emptyList())

        assertSame(plain, SessionIndexJson.liftLegacyKeys(plain, setOf("testType")))
        assertSame(notAnObject, SessionIndexJson.liftLegacyKeys(notAnObject, setOf("testType")))
    }

    @Test
    fun `the extension is installed once`() {
        AppExtensions.install(session = LabExtension)

        val second = runCatching { AppExtensions.install(session = SessionExtension.None) }

        assertTrue(second.exceptionOrNull() is IllegalStateException)
        assertSame(LabExtension, AppExtensions.session)
    }

    private companion object {
        const val GOLDEN = "session/index_golden.json"

        /** Synthesized from the fork's record fields (mt:data/session/SessionRecord.kt, 2026-10). */
        const val FORK = "session/index_fork_2026_10.json"
    }
}
