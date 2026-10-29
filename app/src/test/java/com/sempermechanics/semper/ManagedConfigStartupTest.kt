package com.sempermechanics.semper

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.policy.ManagedConfigReader
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import timber.log.Timber
import java.util.Collections

/**
 * [SemperApp.onCreate] starts the managed-configuration read off the main
 * thread (readiness TE1-105), so the viewer's share check finds it cached.
 * [ManagedConfigWarmupTest][com.sempermechanics.semper.data.policy.ManagedConfigWarmupTest]
 * covers what the warm-up does; this covers that start-up asks for it, and
 * not on main.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application under the test, so the SemperApp started here is the
// only one, and it starts with nothing read.
@Config(application = Application::class)
class ManagedConfigStartupTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The threads the restrictions were read on, through this context. */
    private val readers: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    private val recording = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this

        override fun getSystemService(name: String): Any? {
            if (name == RESTRICTIONS_SERVICE) readers += Thread.currentThread()
            return super.getSystemService(name)
        }
    }

    @After
    fun forget() {
        ManagedConfigReader.forgetForTest()
        Timber.uprootAll()
    }

    @Test
    fun `start-up reads the managed configuration, off the main thread`() {
        ManagedConfigReader.forgetForTest()
        val semper = SemperApp()
        ReflectionHelpers.callInstanceMethod<Unit>(
            semper,
            "attachBaseContext",
            ClassParameter.from(Context::class.java, recording),
        )

        semper.onCreate()

        idleUntil("the start-up read of the managed configuration") { readers.isNotEmpty() }
        val main = Looper.getMainLooper().thread
        assertTrue("read on the main thread: $readers", readers.none { it === main })
    }
}
