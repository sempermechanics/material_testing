package com.sempermechanics.semper.data.policy

import android.app.Application
import android.content.ContextWrapper
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The first [ManagedConfigReader.current] of a process read the restrictions
 * (a binder call that reads disk in system_server) on the main thread when
 * the viewer was the first screen to ask (ResultViewerActivity's share check;
 * once, 18 ms on the API 30 re-verify of readiness TE1-105). SemperApp now
 * reads it at start on IO ([ManagedConfigReader.warm]), so that first
 * current() finds it cached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManagedConfigWarmupTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Counts the restriction reads made through it. */
    private inner class Counting : ContextWrapper(app) {
        var reads = 0

        override fun getApplicationContext() = this

        override fun getSystemService(name: String): Any? {
            if (name == RESTRICTIONS_SERVICE) reads++
            return super.getSystemService(name)
        }
    }

    @After
    fun forget() = ManagedConfigReader.forgetForTest()

    @Test
    fun `after the warm-up the first current() reads nothing`() {
        val rm = app.getSystemService(RestrictionsManager::class.java)
        shadowOf(rm).setApplicationRestrictions(Bundle().apply { putString("accountEmailDomain", "a.example") })
        ManagedConfigReader.forgetForTest()
        val context = Counting()

        val worker = Thread { ManagedConfigReader.warm(context) }
        worker.start()
        worker.join()
        assertEquals("the warm-up reads once", 1, context.reads)

        val first = ManagedConfigReader.current(context)
        assertEquals("the first current() on main read again", 1, context.reads)
        assertEquals("a.example", first.accountEmailDomain)
    }

    @Test
    fun `a warm-up after a read keeps what was read`() {
        ManagedConfigReader.forgetForTest()
        val context = Counting()
        ManagedConfigReader.current(context)

        ManagedConfigReader.warm(context)

        assertEquals("warm read again", 1, context.reads)
    }
}
