package com.sempermechanics.semper.data.policy

import android.app.Application
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coming back to the foreground re-read the managed configuration with a
 * binder call (and a disk read in system_server) on the main thread, every
 * time (readiness TE1-105). The read now runs on the scope it is given, off
 * the thread that starts the screen; callers keep reading the cached value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManagedConfigForegroundTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun restrict(domain: String) {
        val rm = app.getSystemService(RestrictionsManager::class.java)
        shadowOf(rm).setApplicationRestrictions(Bundle().apply { putString("accountEmailDomain", domain) })
    }

    @Test
    fun `the foreground re-read runs on the given scope, not inline`() {
        restrict("a.example")
        val before = ManagedConfigReader.refresh(app)
        val dispatcher = StandardTestDispatcher()
        val scope: CoroutineScope = TestScope(dispatcher)
        restrict("b.example")

        ManagedConfigReader.foregroundListener(app, scope).onForeground()
        // Nothing read on the calling (main) thread yet.
        assertEquals(before, ManagedConfigReader.current(app))

        dispatcher.scheduler.advanceUntilIdle()
        assertNotEquals(before, ManagedConfigReader.current(app))
    }
}
