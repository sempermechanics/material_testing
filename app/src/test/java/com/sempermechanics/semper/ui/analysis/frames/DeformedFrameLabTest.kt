package com.sempermechanics.semper.ui.analysis.frames

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a load is matched by rides with its frame: a photo's capture time and
 * bending's typed hanger mass follow the photo when the list is re-sorted or
 * dragged, as its name and date do.
 */
class DeformedFrameLabTest {

    private val frames = listOf(
        DeformedFrame("/f/2.png", "c.png", captureTimeMs = 3_000L, typedLoadKg = 1.5f),
        DeformedFrame("/f/0.png", "a.png", captureTimeMs = 1_000L, typedLoadKg = 0.5f),
        DeformedFrame("/f/1.png", "b.png"),
    )

    @Test
    fun `capture times and typed loads move with their frames`() {
        val out = FrameOrderHelper.reorder(frames, FrameOrderMode.NAME)

        assertEquals(listOf("/f/0.png", "/f/1.png", "/f/2.png"), out.map { it.path })
        assertEquals(listOf(1_000L, null, 3_000L), out.map { it.captureTimeMs })
        assertEquals(listOf(0.5f, null, 1.5f), out.map { it.typedLoadKg })
    }

    @Test
    fun `typed loads follow a manual drag`() {
        val out = FrameOrderHelper.reorder(frames, FrameOrderMode.MANUAL, manualOrder = listOf(2, 0, 1))

        assertEquals(listOf(null, 1.5f, 0.5f), out.map { it.typedLoadKg })
    }
}
