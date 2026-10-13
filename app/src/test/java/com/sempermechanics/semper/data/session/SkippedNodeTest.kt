package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkippedNodeTest {

    @Test
    fun `json round trip preserves nodes`() {
        val nodes = listOf(
            SkippedNode(subset = 41, step = 5, strainWindow = 15, code = 12),
            SkippedNode(subset = 33, step = 3, strainWindow = 11, code = 7),
        )
        assertEquals(nodes, SkippedNode.decodeJson(SkippedNode.encodeJson(nodes)))
    }

    @Test
    fun `json round trip keeps a node with no recorded code apart from the strain-window code`() {
        val nodes = listOf(SkippedNode(41, 5, 15, code = null), SkippedNode(33, 3, 11, code = 0))
        val text = SkippedNode.encodeJson(nodes)

        assertEquals(nodes, SkippedNode.decodeJson(text))
        assertFalse("a null code is left out, not written as null", "null" in text)
    }

    @Test
    fun `json from before a node could lack a code reads a missing code as null`() {
        val nodes = SkippedNode.decodeJson("""[{"subset":41,"step":5,"strainWindow":15}]""")
        assertNull(nodes.single().code)
    }

    @Test
    fun `legacy arrays import into typed nodes`() {
        val nodes = SkippedNode.fromLegacyArrays(
            subsets = listOf(41, 33),
            steps = listOf(5, 3),
            strainWindows = listOf(15, 11),
            codes = listOf(12, 7),
        )
        assertEquals(2, nodes.size)
        assertEquals(41, nodes[0].subset)
        assertEquals(7, nodes[1].code)
    }

    @Test
    fun `legacy arrays with mismatched lengths fail loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            SkippedNode.fromLegacyArrays(
                subsets = listOf(41),
                steps = listOf(5, 3),
                strainWindows = listOf(15),
                codes = listOf(12),
            )
        }
    }

    @Test
    fun `legacy arrays saved before codes were kept read each node with no code`() {
        val nodes = SkippedNode.fromLegacyArrays(
            subsets = listOf(41, 33),
            steps = listOf(5, 3),
            strainWindows = listOf(15, 11),
            codes = emptyList(),
        )
        assertEquals(
            listOf(SkippedNode(41, 5, 15, null), SkippedNode(33, 3, 11, null)),
            nodes,
        )
    }

    @Test
    fun `a codes list shorter than the combinations is padded with no code`() {
        val nodes = SkippedNode.fromLegacyArrays(listOf(41, 33), listOf(5, 3), listOf(15, 11), listOf(-12))
        assertEquals(listOf(-12, null), nodes.map { it.code })
    }

    @Test
    fun `more codes than combinations still fail loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            SkippedNode.fromLegacyArrays(listOf(41), listOf(5), listOf(15), listOf(-12, -3))
        }
    }

    @Test
    fun `a local sweep row saved before codes were kept still opens`() {
        val row = sessionRecord(id = "old-sweep").copy(
            sweepSkipSubsets = listOf(41),
            sweepSkipSteps = listOf(9),
            sweepSkipStrainWindows = listOf(121),
        )
        assertEquals(listOf(SkippedNode(41, 9, 121, null)), row.resolvedSkipNodes())
    }

    @Test
    fun `a failed sweep keeps each node's own code`() {
        val recorded = listOf(
            SkippedNode(subset = 21, step = 5, strainWindow = 15, code = 12),
            SkippedNode(subset = 25, step = 5, strainWindow = 15, code = 7),
        )
        val nodes = SkippedNode.forFailedSweep(recorded) { error("the plan is only a fallback") }
        assertEquals(listOf(12, 7), nodes.map { it.code })
    }

    @Test
    fun `a sweep that recorded nothing falls back to the plan`() {
        val planned = listOf(SkippedNode(subset = 21, step = 5, strainWindow = 15, code = 3))
        assertEquals(planned, SkippedNode.forFailedSweep(emptyList()) { planned })
    }

    @Test
    fun `blank json decodes to empty list`() {
        assertTrue(SkippedNode.decodeJson(null).isEmpty())
        assertTrue(SkippedNode.decodeJson("  ").isEmpty())
    }
}
