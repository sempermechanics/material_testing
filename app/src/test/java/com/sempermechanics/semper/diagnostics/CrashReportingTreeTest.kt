package com.sempermechanics.semper.diagnostics

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [CrashReportingTree]'s wiring of [CrashForwarding]: consent decides whether Crashlytics hears anything. */
@RunWith(RobolectricTestRunner::class)
class CrashReportingTreeTest {

    private class FakeSink : CrashSink {
        val logs = mutableListOf<String>()
        val exceptions = mutableListOf<Throwable>()

        override fun log(message: String) {
            logs += message
        }

        override fun recordException(throwable: Throwable) {
            exceptions += throwable
        }
    }

    private val sink = FakeSink()
    private var consented = false
    private val tree = CrashReportingTree(consented = { consented }, sink = { sink })

    @Test
    fun `the pure plan's levels are android's`() {
        assertEquals(Log.WARN, CrashForwarding.WARN)
        assertEquals(Log.ERROR, CrashForwarding.ERROR)
    }

    @Test
    fun `nothing reaches Crashlytics before consent`() {
        tree.w("quota gate")
        tree.e(IllegalStateException("x"), "upload failed")
        assertTrue(sink.logs.isEmpty())
        assertTrue(sink.exceptions.isEmpty())
    }

    @Test
    fun `after consent warnings are breadcrumbs and errors are non-fatals`() {
        consented = true
        val failure = IllegalStateException("x")
        tree.w("quota gate")
        tree.e(failure, "upload failed")
        tree.d("below the floor")
        assertEquals(2, sink.logs.size)
        assertEquals(listOf<Throwable>(failure), sink.exceptions)
    }

    @Test
    fun `withdrawing consent stops it again`() {
        consented = true
        tree.w("one")
        consented = false
        tree.w("two")
        assertEquals(1, sink.logs.size)
    }
}
