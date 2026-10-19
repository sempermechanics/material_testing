// Fixture videos are assembled pixel by pixel and byte by byte; the numbers are the formats.
@file:Suppress("MagicNumber", "LongParameterList", "TooManyFunctions")

package com.sempermechanics.semper.imaging.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.imaging.GrayPngEncoder
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Video import end to end on a real Android media stack: a clip is encoded
 * here with the platform encoder, then [VideoFrameExtractor.extract] pulls
 * frames out of it exactly as the sampling dialog does.
 *
 * Every frame carries its own index as eight black-or-white blocks, so each
 * extracted PNG says which frame it is. That pins down what the unit tests
 * cannot: that keyframe mode lands on the I-frames, that fixed-interval mode
 * returns the frame asked for rather than the I-frame before it, that the
 * rotation tag is honoured in the right direction, and that each frame came
 * through the lossless Y-plane path (8-bit grayscale PNG) rather than the
 * RGB retriever fallback, which would pass every other check silently.
 * Keyframe mode is checked on each rung: the hardware decoder, the AVI
 * demuxer (indexed, all-intra and index-less files), and the retriever,
 * forced; frames from each carry their own clip times (the retriever's from
 * a sample walk, TD-207).
 */
@RunWith(AndroidJUnit4::class)
class VideoFrameExtractionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "video-extract-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---- MP4 through MediaExtractor + MediaCodec -----------------------------

    @Test
    fun keyframeModeExtractsEachIFrame() {
        val clip = encodeMp4("keyframes.mp4", rotation = 0)
        val frames = extract(clip, preferKeyframes = true, fps = 1.0, endMs = CLIP_MS)
        assertEquals(listOf(0, 30, 60), frames.map { it.code(rotation = 0) })
        frames.forEach { it.assertGrayOfSize(W, H) }
    }

    @Test
    fun fixedIntervalModeDecodesForwardToTheRequestedFrame() {
        val clip = encodeMp4("uniform.mp4", rotation = 0)
        // 5 fps over 0–2 s asks for t = 0, 200, … 2000 ms: frames 0, 6, … 60 at 30 fps.
        val frames = extract(clip, preferKeyframes = false, fps = 5.0, endMs = 2_000)
        assertEquals((0..60 step 6).toList(), frames.map { it.code(rotation = 0) })
        frames.forEach { it.assertGrayOfSize(W, H) }
    }

    @Test
    fun fixedIntervalToTheEndOfTheClipKeepsTheHardwarePath() {
        val clip = encodeMp4("uniform-end.mp4", rotation = 0)
        // The sheet's default segment ends at the duration (3000 ms), past the last
        // frame (2966 ms): that last sample must be the last frame, not a failed
        // decode that drops the whole batch to the retriever's sync-frame seek.
        val frames = extract(clip, preferKeyframes = false, fps = 10.0, endMs = CLIP_MS)
        assertEquals((0..87 step 3).toList() + 89, frames.map { it.code(rotation = 0) })
        frames.forEach { it.assertGrayOfSize(W, H) }
        // TD-207: each frame is timed by its own presentation time, so the last
        // sample (asked for at 3000 ms) reads frame 89's 2966 ms.
        frames.drop(1).forEach { it.assertTimedAsFrame(it.code(rotation = 0)) }
    }

    // ---- Key frames mode on the retriever rung, forced (TD-154) ----------------

    @Test
    fun keyframeModeOnTheRetrieverRungExtractsEachIFrame() {
        val clip = encodeMp4("retriever-keyframes.mp4", rotation = 0)
        val frames = extractWithRetriever(clip, preferKeyframes = true, fps = 5.0, endMs = CLIP_MS)
        // At 5 fps a uniform plan would hold 15 frames; the I-frames are three.
        assertEquals(listOf(0, 30, 60), frames.map { it.code(rotation = 0) })
        // TD-207: each I-frame is timed by its own presentation time.
        frames.drop(1).forEach { it.assertTimedAsFrame(it.code(rotation = 0)) }
    }

    @Test
    fun fixedIntervalOnTheRetrieverRungTimesEachFrameByItsOwnTime() {
        val clip = encodeMp4("retriever-uniform.mp4", rotation = 0)
        // 7 fps asks for t = 0, 142, 285, … ms, none of them a 30 fps frame's start:
        // each frame reads the time of the frame the retriever returned (TD-207).
        val frames = extractWithRetriever(clip, preferKeyframes = false, fps = 7.0, endMs = 2_000)
        assertTrue("too few frames: ${frames.size}", frames.size > 2)
        frames.drop(1).forEach { it.assertTimedAsFrame(it.code(rotation = 0)) }
    }

    @Test
    fun portraitClipComesOutUpright() {
        val clip = encodeMp4("portrait.mp4", rotation = 90)
        val frames = extract(clip, preferKeyframes = true, fps = 1.0, endMs = CLIP_MS)
        assertEquals(listOf(0, 30, 60), frames.map { it.code(rotation = 90) })
        frames.forEach { it.assertGrayOfSize(H, W) }
    }

    // ---- AVI through AviVideoDecoder ----------------------------------------

    @Test
    fun uncompressedAviIsBitExact() {
        val clip = writeAvi("y800.avi", compression = "Y800", bitCount = 8, extra = ByteArray(0)) { i ->
            AviPayload(lumaOf(i), keyframe = true)
        }
        // Key frames mode on an all-intra AVI: every frame is a keyframe, so it samples uniformly.
        val frames = extract(clip, preferKeyframes = true, fps = 5.0, endMs = 2_000)
        assertEquals((0..60 step 6).toList(), frames.map { it.code(rotation = 0) })
        frames.forEach { it.assertGrayOfSize(W, H) }
        // Lossless: every pixel of the reference is the byte that went in.
        val expected = lumaOf(0)
        val ref = frames.first().bitmap
        for (y in 0 until H) {
            for (x in 0 until W) {
                assertEquals("pixel ($x,$y)", expected[y * W + x].toInt() and 0xFF, ref.getPixel(x, y) and 0xFF)
            }
        }
    }

    @Test
    fun mjpegAviDecodes() {
        val clip = writeAvi("mjpg.avi", compression = "MJPG", bitCount = 24, extra = ByteArray(0)) { i ->
            AviPayload(jpegOf(lumaOf(i)), keyframe = true)
        }
        val frames = extract(clip, preferKeyframes = true, fps = 5.0, endMs = 2_000)
        assertEquals((0..60 step 6).toList(), frames.map { it.code(rotation = 0) })
        frames.forEach { it.assertGrayOfSize(W, H) }
    }

    @Test
    fun indexLessAviInKeyframeModeSamplesUniformly() {
        val clip = writeAvi(
            "y800-noindex.avi",
            compression = "Y800",
            bitCount = 8,
            extra = ByteArray(0),
            index = false,
        ) { AviPayload(lumaOf(it), keyframe = it % 30 == 0) }
        // No idx1: the reader assumes every frame a keyframe, which says nothing.
        val frames = extract(clip, preferKeyframes = true, fps = 5.0, endMs = 2_000)
        assertEquals((0..60 step 6).toList(), frames.map { it.code(rotation = 0) })
        frames.forEach { it.assertGrayOfSize(W, H) }
    }

    @Test
    fun h264InAviDecodesThroughThePlatformCodec() {
        val frames = extract(h264Avi("h264.avi"), preferKeyframes = false, fps = 5.0, endMs = 2_000)
        assertEquals((0..60 step 6).toList(), frames.map { it.code(rotation = 0) })
        frames.forEach { it.assertGrayOfSize(W, H) }
    }

    @Test
    fun keyframeModeOnAnIndexedH264AviExtractsEachIFrame() {
        // TD-154: the AVI rung reads the idx1 keyframe flags, one I-frame a second.
        val frames = extract(h264Avi("h264-keyframes.avi"), preferKeyframes = true, fps = 5.0, endMs = CLIP_MS)
        assertEquals(listOf(0, 30, 60), frames.map { it.code(rotation = 0) })
        frames.forEach { it.assertGrayOfSize(W, H) }
        frames.drop(1).forEach { it.assertTimedAsFrame(it.code(rotation = 0)) }
    }

    private fun h264Avi(name: String): File {
        val encoded = encodeAvc(onFormat = {}) { _, _, _ -> }
        return writeAvi(name, compression = "H264", bitCount = 24, extra = encoded.csd) { i ->
            val sample = encoded.samples[i]
            AviPayload(if (i == 0) encoded.csd + sample.bytes else sample.bytes, sample.keyframe)
        }
    }

    // ---- extraction ----------------------------------------------------------

    /** One extracted frame: its PNG and its time in the clip (null for the reference, which has none). */
    private class Frame(val png: ByteArray, val timeMs: Long? = null) {
        val bitmap: Bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "undecodable PNG" }

        /** Timed as source frame [index] starts, to the millisecond the container rounds to. */
        fun assertTimedAsFrame(index: Int) {
            val expected = index * 1000L / FPS
            val actual = requireNotNull(timeMs) { "frame $index has no clip time" }
            assertTrue("frame $index timed $actual ms, expected $expected", abs(actual - expected) <= 1)
        }

        /**
         * Written by [GrayPngEncoder] — the Y-plane path — and not by the retriever
         * fallback. Both emit 8-bit gray PNGs with the same three chunks, so only
         * the exact bytes tell them apart: re-encoding the pixels must reproduce
         * the file.
         */
        fun assertGrayOfSize(width: Int, height: Int) {
            assertEquals("width", width, bitmap.width)
            assertEquals("height", height, bitmap.height)
            val luma = ByteArray(width * height) { i -> bitmap.getPixel(i % width, i / width).toByte() }
            val reencoded = ByteArrayOutputStream()
                .also { GrayPngEncoder.encode(it, GrayPngEncoder.Luma(luma, width, height, rowStride = width)) }
                .toByteArray()
            assertArrayEquals("not written by GrayPngEncoder (retriever fallback?)", reencoded, png)
        }

        /** The frame index stamped into the source, read back through [rotation]. */
        fun code(rotation: Int): Int {
            var value = 0
            for (bit in 0 until BITS) {
                val cx = BLOCK_X + bit * BLOCK + BLOCK / 2
                val cy = BLOCK_Y + BLOCK / 2
                var sum = 0
                for (dy in -4..4) {
                    for (dx in -4..4) {
                        val (ox, oy) = upright(cx + dx, cy + dy, rotation)
                        sum += bitmap.getPixel(ox, oy) and 0xFF
                    }
                }
                if (sum / 81 > 128) value = value or (1 shl bit)
            }
            return value
        }

        /** Where source pixel ([x], [y]) lands once a clip tagged [rotation] is shown upright. */
        private fun upright(x: Int, y: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
            90 -> (H - 1 - y) to x
            180 -> (W - 1 - x) to (H - 1 - y)
            270 -> y to (W - 1 - x)
            else -> x to y
        }
    }

    private fun request(file: File, preferKeyframes: Boolean, fps: Double, endMs: Long) = ExtractionRequest(
        uri = Uri.fromFile(file),
        fpsExtract = fps,
        startMs = 0,
        endMs = endMs,
        maxFrames = 100,
        preferKeyframes = preferKeyframes,
    )

    /** Every rung in order, as the sampling dialog runs them. */
    private fun extract(file: File, preferKeyframes: Boolean, fps: Double, endMs: Long): List<Frame> {
        val out = File(dir, "out-${file.nameWithoutExtension}").apply { mkdirs() }
        val result = runBlocking(Dispatchers.IO) {
            VideoFrameExtractor.extract(
                context = context,
                request = request(file, preferKeyframes, fps, endMs),
                cacheDir = out,
                onProgress = { _, _ -> },
            )
        }
        return framesOf(file, result)
    }

    /** The retriever rung alone, which the hardware decoder otherwise takes every MP4 from. */
    private fun extractWithRetriever(file: File, preferKeyframes: Boolean, fps: Double, endMs: Long): List<Frame> {
        val out = File(dir, "out-retriever-${file.nameWithoutExtension}").apply { mkdirs() }
        val staging = FrameImportHelper.createStagingDir(out)
        val result = try {
            runBlocking(Dispatchers.IO) {
                VideoFrameExtractor.extractWithRetriever(
                    context = context,
                    request = request(file, preferKeyframes, fps, endMs),
                    sink = FrameSink(context, out, staging, clipName = file.name, onProgress = { _, _ -> }),
                )
            }
        } finally {
            staging.deleteRecursively()
        }
        return framesOf(file, result)
    }

    private fun framesOf(file: File, result: VideoFrameExtractor.ExtractionResult?): List<Frame> {
        assertNotNull("extraction returned nothing for ${file.name}", result)
        result!!
        return listOf(Frame(result.reference.png)) +
            result.batch.frames.map { Frame(File(it.path).readBytes(), it.timeMs) }
    }

    // ---- source frames -------------------------------------------------------

    /** Frame [index]: a soft gradient with the index in eight blocks along the top. */
    private fun lumaOf(index: Int): ByteArray {
        val luma = ByteArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) luma[y * W + x] = (64 + (x + y) % 64).toByte()
        }
        for (bit in 0 until BITS) {
            val v = if (index and (1 shl bit) != 0) 235 else 16
            for (y in BLOCK_Y until BLOCK_Y + BLOCK) {
                for (x in BLOCK_X + bit * BLOCK until BLOCK_X + (bit + 1) * BLOCK) luma[y * W + x] = v.toByte()
            }
        }
        return luma
    }

    private fun jpegOf(luma: ByteArray): ByteArray {
        val pixels = IntArray(W * H) { i ->
            val v = luma[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(pixels, W, H, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    // ---- H.264 encoding ------------------------------------------------------

    private class Sample(val bytes: ByteArray, val keyframe: Boolean, val ptsUs: Long)

    private class Encoded(val csd: ByteArray, val samples: List<Sample>)

    /** Encodes [FRAMES] frames at [FPS] with one I-frame a second. */
    private fun encodeAvc(
        onFormat: (MediaFormat) -> Unit,
        onSample: (java.nio.ByteBuffer, MediaCodec.BufferInfo, Boolean) -> Unit,
    ): Encoded {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val samples = mutableListOf<Sample>()
        var csd = ByteArray(0)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var done = false
            while (!done) {
                if (fed <= FRAMES) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        if (fed == FRAMES) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            fillInput(codec, inIndex, lumaOf(fed))
                            codec.queueInputBuffer(inIndex, 0, W * H * 3 / 2, fed * 1_000_000L / FPS, 0)
                        }
                        fed++
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                    outIndex >= 0 -> {
                        val buf = requireNotNull(codec.getOutputBuffer(outIndex))
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also { buf.duplicate().get(it) }
                            if (isConfig) {
                                csd += bytes
                            } else {
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                samples += Sample(bytes, key, info.presentationTimeUs)
                                onSample(buf, info, key)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        assertEquals("encoded frame count", FRAMES, samples.size)
        return Encoded(csd, samples)
    }

    private fun fillInput(codec: MediaCodec, index: Int, luma: ByteArray) {
        val image = requireNotNull(codec.getInputImage(index)) { "encoder has no flexible input image" }
        val y = image.planes[0]
        for (row in 0 until H) {
            for (col in 0 until W) y.buffer.put(row * y.rowStride + col * y.pixelStride, luma[row * W + col])
        }
        for (p in 1..2) {
            val plane = image.planes[p]
            for (row in 0 until H / 2) {
                for (col in 0 until W / 2) {
                    plane.buffer.put(row * plane.rowStride + col * plane.pixelStride, 128.toByte())
                }
            }
        }
    }

    private fun encodeMp4(name: String, rotation: Int): File {
        val file = File(dir, name)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        var track = -1
        try {
            encodeAvc(
                onFormat = { format ->
                    track = muxer.addTrack(format)
                    muxer.start()
                },
            ) { buf, info, _ -> muxer.writeSampleData(track, buf, info) }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return file
    }

    // ---- AVI writing ---------------------------------------------------------

    private class AviPayload(val bytes: ByteArray, val keyframe: Boolean)

    private fun writeAvi(
        name: String,
        compression: String,
        bitCount: Int,
        extra: ByteArray,
        index: Boolean = true,
        frame: (Int) -> AviPayload,
    ): File {
        val payloads = (0 until FRAMES).map(frame)
        val chunkId = if (compression == "Y800") "00db" else "00dc"
        val movi = ByteArrayOutputStream()
        payloads.forEach { movi.write(chunk(chunkId, it.bytes)) }
        val idx1 = ByteArrayOutputStream()
        payloads.forEach {
            idx1.write(fcc(chunkId))
            idx1.write(le32(if (it.keyframe) 0x10 else 0))
            idx1.write(le32(0))
            idx1.write(le32(it.bytes.size))
        }
        val indexChunk = if (index) chunk("idx1", idx1.toByteArray()) else ByteArray(0)
        val header = list(
            "hdrl",
            avih(1_000_000 / FPS, W, H) +
                list("strl", strh("vids", compression, 1, FPS) + strf(W, H, bitCount, compression, extra)),
        )
        val file = File(dir, name)
        file.writeBytes(riff("AVI ", header + list("movi", movi.toByteArray()) + indexChunk))
        return file
    }

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

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

    private fun riff(type: String, body: ByteArray) = chunk("RIFF", fcc(type) + body)

    private fun avih(microSecPerFrame: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(56)
        le32(microSecPerFrame).copyInto(body, 0)
        le32(FRAMES).copyInto(body, 16)
        le32(width).copyInto(body, 32)
        le32(height).copyInto(body, 36)
        return chunk("avih", body)
    }

    private fun strh(type: String, handler: String, scale: Int, rate: Int): ByteArray {
        val body = ByteArray(56)
        fcc(type).copyInto(body, 0)
        fcc(handler).copyInto(body, 4)
        le32(scale).copyInto(body, 20)
        le32(rate).copyInto(body, 24)
        le32(FRAMES).copyInto(body, 32)
        return chunk("strh", body)
    }

    private fun strf(width: Int, height: Int, bitCount: Int, compression: String, extra: ByteArray): ByteArray {
        val body = ByteArray(40)
        le32(40).copyInto(body, 0)
        le32(width).copyInto(body, 4)
        le32(height).copyInto(body, 8)
        le16(1).copyInto(body, 12)
        le16(bitCount).copyInto(body, 14)
        fcc(compression).copyInto(body, 16)
        return chunk("strf", body + extra)
    }

    private companion object {
        const val W = 320
        const val H = 240
        const val FPS = 30
        const val FRAMES = 90
        const val CLIP_MS = 3_000L

        const val BITS = 8
        const val BLOCK = 32
        const val BLOCK_X = 16
        const val BLOCK_Y = 16
    }
}
