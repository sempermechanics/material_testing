package com.sempermechanics.semper.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashForwardingTest {

    @Test
    fun `without consent nothing is logged, breadcrumbed or recorded`() {
        val plan =
            CrashForwarding.plan(CrashForwarding.ERROR, "Upload", "failed", hasThrowable = true, consented = false)
        assertFalse(plan.logcat)
        assertNull(plan.breadcrumb)
        assertFalse(plan.recordException)
    }

    @Test
    fun `with consent a warning is a breadcrumb only`() {
        val plan = CrashForwarding.plan(CrashForwarding.WARN, "Upload", "slow", hasThrowable = true, consented = true)
        assertTrue(plan.logcat)
        assertEquals("Upload: slow", plan.breadcrumb)
        assertFalse(plan.recordException)
    }

    @Test
    fun `with consent an error with a throwable is a non-fatal`() {
        val plan = CrashForwarding.plan(CrashForwarding.ERROR, null, "boom", hasThrowable = true, consented = true)
        assertEquals("boom", plan.breadcrumb)
        assertEquals("Semper", plan.tag)
        assertTrue(plan.recordException)
        val noThrowable =
            CrashForwarding.plan(CrashForwarding.ERROR, null, "boom", hasThrowable = false, consented = true)
        assertFalse(noThrowable.recordException)
    }

    @Test
    fun `a long tag is cut to logcat's limit but kept whole in the breadcrumb`() {
        val tag = "A".repeat(40)
        val plan = CrashForwarding.plan(CrashForwarding.WARN, tag, "m", hasThrowable = false, consented = true)
        assertEquals(CrashForwarding.MAX_TAG_LEN, plan.tag.length)
        assertEquals("$tag: m", plan.breadcrumb)
    }
}
