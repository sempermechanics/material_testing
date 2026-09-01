package com.indicvision.semper.data

import org.junit.Assert.assertEquals
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
    fun `blank json decodes to empty list`() {
        assertTrue(SkippedNode.decodeJson(null).isEmpty())
        assertTrue(SkippedNode.decodeJson("  ").isEmpty())
    }
}
