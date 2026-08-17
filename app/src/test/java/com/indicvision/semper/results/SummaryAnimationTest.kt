package com.indicvision.semper.results

import com.indicvision.semper.DicResult
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.viewer.SummaryAnimation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The two decisions the animation rests on: how long a frame is shown, and what
 * colour scale every frame is drawn against.
 */
class SummaryAnimationTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** One frame of correlated points whose Exx spans [low, high]. */
    private fun frame(name: String, low: Float, high: Float, points: Int = 100): File {
        val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (i in 0 until points) {
            val t = i.toFloat() / (points - 1)
            buffer.putFloat((i % 10) * 4f) // x
            buffer.putFloat((i / 10) * 4f) // y
            buffer.putFloat(0f) // u
            buffer.putFloat(0f) // v
            buffer.putFloat(low + t * (high - low)) // exx
            buffer.putFloat(0f) // eyy
            buffer.putFloat(0f) // exy
            buffer.putFloat(0.01f) // znssd — accepted
        }
        return temp.newFile(name).apply { writeBytes(buffer.array()) }
    }

    // ── delayCentis ──────────────────────────────────────────────────────

    @Test
    fun `a short sequence gets the preferred 300ms a frame`() {
        for (frames in 1..33) {
            assertEquals("$frames frames", SummaryAnimation.PREFERRED_CENTIS, SummaryAnimation.delayCentis(frames))
        }
    }

    @Test
    fun `a long sequence keeps every frame and stays inside the ceiling`() {
        // Max frames per analysis is 150; nothing may be dropped to fit 10 s.
        for (frames in 34..150) {
            val delay = SummaryAnimation.delayCentis(frames)
            val total = frames * delay
            assertTrue("$frames frames ran to $total cs", total <= SummaryAnimation.MAX_TOTAL_CENTIS)
            assertTrue("$frames frames used $delay cs", delay >= SummaryAnimation.MIN_CENTIS)
            assertTrue("$frames frames should not exceed the preference", delay <= SummaryAnimation.PREFERRED_CENTIS)
        }
    }

    @Test
    fun `150 frames runs for about nine seconds at 60ms each`() {
        assertEquals(6, SummaryAnimation.delayCentis(150))
    }

    @Test
    fun `an absurd frame count still yields a delay a viewer will honour`() {
        assertEquals(SummaryAnimation.MIN_CENTIS, SummaryAnimation.delayCentis(100_000))
    }

    @Test
    fun `no frames is not a division by zero`() {
        assertEquals(SummaryAnimation.PREFERRED_CENTIS, SummaryAnimation.delayCentis(0))
    }

    @Test
    fun `gif cache filename includes the canvas colour`() {
        val anim = SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = emptyList(),
                imgW = 1,
                imgH = 1,
                stepAt = { 1 },
                outputDir = temp.root,
                backgroundColor = 0xFFF4F9FC.toInt(),
            ),
        )
        assertEquals("U_animation_FFF4F9FC.gif", anim.fileFor("U").name)
    }

    // ── globalRanges ─────────────────────────────────────────────────────

    @Test
    fun `the range spans every frame, not just the last`() {
        // Frame 2 holds the lowest value, frame 3 the highest: neither alone
        // describes the sequence.
        val files = listOf(
            frame("a.dat", 0f, 1f),
            frame("b.dat", -5f, 0.5f),
            frame("c.dat", 0f, 9f),
        )

        val exx = runBlocking { SummaryAnimation.globalRanges(files) }.getValue(DicResult.IDX_EXX)

        assertTrue("low ${exx.first} should come from the second frame", exx.first < -4f)
        assertTrue("high ${exx.second} should come from the third frame", exx.second > 8f)
    }

    @Test
    fun `all five fields come back from one pass`() {
        val ranges = runBlocking { SummaryAnimation.globalRanges(listOf(frame("a.dat", 0f, 1f))) }

        for ((label, index) in SummaryAnimation.FIELDS) {
            assertTrue("$label missing", ranges.containsKey(index))
        }
    }

    @Test
    fun `unreadable frames are skipped rather than aborting the pass`() {
        val broken = temp.newFile("broken.dat").apply { writeBytes(ByteArray(7)) }
        val files = listOf(broken, frame("good.dat", 0f, 4f))

        val exx = runBlocking { SummaryAnimation.globalRanges(files) }.getValue(DicResult.IDX_EXX)

        assertTrue("high ${exx.second} should still reflect the good frame", exx.second > 3f)
    }

    @Test
    fun `no frames yields no ranges`() {
        assertTrue(runBlocking { SummaryAnimation.globalRanges(emptyList()) }.isEmpty())
    }

    @Test
    fun `globalRanges reports progress for every frame including unreadable`() = runBlocking {
        val broken = temp.newFile("broken.dat").apply { writeBytes(ByteArray(7)) }
        val files = listOf(broken, frame("good.dat", 0f, 4f))
        val ticks = mutableListOf<Pair<Int, Int>>()

        SummaryAnimation.globalRanges(files) { done, total -> ticks += done to total }

        assertEquals(listOf(1 to 2, 2 to 2), ticks)
    }

    // ── globalRanges: cached (FieldRangesStore) vs decoded must agree exactly ──

    private val summaryFieldIndices = intArrayOf(
        DicResult.IDX_U,
        DicResult.IDX_V,
        DicResult.IDX_EXX,
        DicResult.IDX_EYY,
        DicResult.IDX_EXY,
    )

    /** [FieldRangesStore.write]'s input, computed the same way AnalysisViewModel does. */
    private fun rangesFileFor(files: List<File>): File {
        val perFrame = files.map { f ->
            DicResult.decodeDatFile(f)?.let { VisualizationEngine.valueRanges(it, summaryFieldIndices) }
                ?: emptyMap()
        }
        val out = temp.newFile("field_ranges_${files.hashCode()}.bin")
        FieldRangesStore.write(out, summaryFieldIndices, perFrame)
        return out
    }

    @Test
    fun `a valid cache produces exactly the same ranges as decoding every frame`() = runBlocking {
        val files = listOf(
            frame("a.dat", 0f, 1f),
            frame("b.dat", -5f, 0.5f),
            frame("c.dat", 0f, 9f),
        )
        val rangesFile = rangesFileFor(files)

        val decoded = SummaryAnimation.globalRanges(files)
        val cached = SummaryAnimation.globalRanges(files, rangesFile)

        assertEquals(decoded, cached)
    }

    @Test
    fun `a missing cache file falls back to decoding, same result`() = runBlocking {
        val files = listOf(frame("a.dat", 0f, 1f), frame("b.dat", -5f, 0.5f))
        val missingCache = temp.root.resolve("does_not_exist.bin")

        val decoded = SummaryAnimation.globalRanges(files)
        val fallback = SummaryAnimation.globalRanges(files, missingCache)

        assertEquals(decoded, fallback)
    }

    @Test
    fun `a cache whose frame count no longer matches the batch falls back to decoding`() = runBlocking {
        // Simulates a batch edited after the cache was written (e.g. a re-run that
        // added a frame) — the stale cache must never be trusted over the real files.
        val originalFiles = listOf(frame("a.dat", 0f, 1f), frame("b.dat", -5f, 0.5f))
        val staleCache = rangesFileFor(originalFiles)
        val grownFiles = originalFiles + frame("c.dat", 0f, 9f)

        val decoded = SummaryAnimation.globalRanges(grownFiles)
        val withStaleCache = SummaryAnimation.globalRanges(grownFiles, staleCache)

        assertEquals(decoded, withStaleCache)
        // Not the vacuous case — the stale cache really did omit the third frame's range.
        assertTrue(decoded.getValue(DicResult.IDX_EXX).second > 8f)
    }

    @Test
    fun `cached progress reporting still covers every frame`() = runBlocking {
        val files = listOf(frame("a.dat", 0f, 1f), frame("b.dat", -5f, 0.5f))
        val rangesFile = rangesFileFor(files)
        val ticks = mutableListOf<Pair<Int, Int>>()

        SummaryAnimation.globalRanges(files, rangesFile) { done, total -> ticks += done to total }

        assertEquals(listOf(1 to 2, 2 to 2), ticks)
    }
}
