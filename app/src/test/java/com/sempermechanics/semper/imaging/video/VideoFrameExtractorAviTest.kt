package com.sempermechanics.semper.imaging.video

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.imaging.GrayPngEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The AVI rung end to end: an uncompressed 8-bit AVI, written here byte by
 * byte, through [VideoFrameExtractor.extract]. Key frames mode follows the
 * `idx1` flags (TD-153), and every frame keeps its own start time in the clip
 * and the exact bytes that went in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VideoFrameExtractorAviTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `Key frames mode on an indexed AVI extracts the keyframes`() {
        val result = extract(writeAvi { it % KEY_EVERY == 0 }, preferKeyframes = true)

        assertEquals(listOf(480L, 960L, 1440L, 1920L), result.batch.frameTimesMs)
        assertArrayEquals(pngOf(0), result.reference.png)
        result.batch.filePaths.zip(listOf(12, 24, 36, 48)).forEach { (path, frame) ->
            assertArrayEquals("frame $frame", pngOf(frame), File(path).readBytes())
        }
    }

    @Test
    fun `Key frames mode on an all-keyframe AVI samples at the slider's rate`() {
        val result = extract(writeAvi { true }, preferKeyframes = true)

        assertEquals((200L..1800L step 200).toList(), result.batch.frameTimesMs)
        result.batch.filePaths.zip((5..45 step 5).toList()).forEach { (path, frame) ->
            assertArrayEquals("frame $frame", pngOf(frame), File(path).readBytes())
        }
    }

    @Test
    fun `Fixed interval mode on an indexed AVI ignores its keyframes`() {
        val result = extract(writeAvi { it % KEY_EVERY == 0 }, preferKeyframes = false)

        assertEquals((200L..1800L step 200).toList(), result.batch.frameTimesMs)
    }

    private fun extract(file: File, preferKeyframes: Boolean): VideoFrameExtractor.ExtractionResult =
        checkNotNull(
            runBlocking {
                VideoFrameExtractor.extract(
                    context = context,
                    request = ExtractionRequest(
                        uri = Uri.fromFile(file),
                        fpsExtract = 5.0,
                        startMs = 0,
                        endMs = 1960,
                        maxFrames = 100,
                        preferKeyframes = preferKeyframes,
                        rotationDegrees = 0,
                    ),
                    cacheDir = temp.newFolder(),
                    onProgress = { _, _ -> },
                )
            },
        ) { "extraction returned nothing" }

    /** Frame [index] is flat gray at level [index], so its PNG says which frame it is. */
    private fun lumaOf(index: Int) = ByteArray(W * H) { index.toByte() }

    private fun pngOf(index: Int): ByteArray = ByteArrayOutputStream()
        .also { GrayPngEncoder.encode(it, GrayPngEncoder.Luma(lumaOf(index), W, H, rowStride = W)) }
        .toByteArray()

    // ---- AVI writing ---------------------------------------------------------

    /** [FRAMES] Y800 frames at [FPS], with an `idx1` flagging the frames [key] picks. */
    private fun writeAvi(key: (Int) -> Boolean): File {
        val movi = ByteArrayOutputStream()
        val index = ByteArrayOutputStream()
        for (i in 0 until FRAMES) {
            movi.write(chunk("00db", lumaOf(i)))
            index.write(fcc("00db"))
            index.write(le32(if (key(i)) KEYFRAME_FLAG else 0))
            index.write(le32(0))
            index.write(le32(W * H))
        }
        val avih = ByteArray(AVIH_SIZE).also {
            le32(1_000_000 / FPS).copyInto(it, 0)
            le32(FRAMES).copyInto(it, 16)
            le32(W).copyInto(it, 32)
            le32(H).copyInto(it, 36)
        }
        val strh = ByteArray(AVIH_SIZE).also {
            fcc("vids").copyInto(it, 0)
            fcc("Y800").copyInto(it, 4)
            le32(1).copyInto(it, 20)
            le32(FPS).copyInto(it, 24)
            le32(FRAMES).copyInto(it, 32)
        }
        val strf = ByteArray(BITMAPINFO_SIZE).also {
            le32(BITMAPINFO_SIZE).copyInto(it, 0)
            le32(W).copyInto(it, 4)
            le32(H).copyInto(it, 8)
            byteArrayOf(1, 0).copyInto(it, 12)
            byteArrayOf(8, 0).copyInto(it, 14)
            fcc("Y800").copyInto(it, 16)
        }
        val header = list("hdrl", chunk("avih", avih) + list("strl", chunk("strh", strh) + chunk("strf", strf)))
        val body = header + list("movi", movi.toByteArray()) + chunk("idx1", index.toByteArray())
        return temp.newFile("clip.avi").apply { writeBytes(chunk("RIFF", fcc("AVI ") + body)) }
    }

    private fun le32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun fcc(id: String) = id.toByteArray(Charsets.US_ASCII)

    private fun chunk(id: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fcc(id))
        out.write(le32(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun list(type: String, body: ByteArray) = chunk("LIST", fcc(type) + body)

    private companion object {
        const val W = 8
        const val H = 8
        const val FPS = 25
        const val FRAMES = 50
        const val KEY_EVERY = 12
        const val KEYFRAME_FLAG = 0x10
        const val AVIH_SIZE = 56
        const val BITMAPINFO_SIZE = 40
    }
}
