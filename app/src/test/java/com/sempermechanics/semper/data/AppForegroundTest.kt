package com.sempermechanics.semper.data

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AppForeground]: the one count of started screens behind the upload's
 * limit-screen gate, the managed-configuration receiver and the entitlement
 * refresh on return to the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppForegroundTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private class Events : AppForeground.Listener {
        val seen = mutableListOf<String>()

        override fun onForeground() {
            seen += "foreground"
        }

        override fun onBackground() {
            seen += "background"
        }
    }

    @Test
    fun `the first screen to start and the last to stop are told once each`() {
        val events = Events()
        AppForeground.install(app, events)
        assertFalse(AppForeground.isForeground)

        val first = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(AppForeground.isForeground)
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        first.pause().stop()
        assertTrue("one screen is still started", AppForeground.isForeground)
        second.pause().stop()

        assertFalse(AppForeground.isForeground)
        assertEquals(listOf("foreground", "background"), events.seen)
    }

    @Test
    fun `a stop with no start counted is not a trip to the background`() {
        val earlier = Robolectric.buildActivity(Activity::class.java).setup()
        val events = Events()
        AppForeground.install(app, events)

        earlier.pause().stop()

        assertFalse(AppForeground.isForeground)
        assertTrue(events.seen.isEmpty())
    }

    @Test
    fun `a listener with nothing to do in the background need not say so`() {
        var foregrounds = 0
        AppForeground.install(
            app,
            object : AppForeground.Listener {
                override fun onForeground() {
                    foregrounds++
                }
            },
        )

        Robolectric.buildActivity(Activity::class.java).setup().pause().stop()
        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals("each return to the app counts", 2, foregrounds)
    }
}
