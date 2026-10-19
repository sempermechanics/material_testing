package com.sempermechanics.semper.ui.common

import android.app.Application
import android.content.res.ColorStateList
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.CoachPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The first-run tip card: a count and a quiet Skip only when there is more than
 * one tip, "Got it" on the last (or only) tip, and the screen marked seen when
 * the sequence ends either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoachMarkControllerTest {

    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity
    private lateinit var targets: List<View>

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
        activity = controller.get()
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        targets = listOf(Gravity.TOP, Gravity.CENTER, Gravity.BOTTOM).map { gravity ->
            View(activity).also { content.addView(it, FrameLayout.LayoutParams(120, 60, gravity)) }
        }
        settle()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun show(count: Int, screen: CoachPrefs.Screen = CoachPrefs.Screen.SWEEP_LATTICE) {
        CoachMarkController(activity).maybeShow(
            screen,
            targets.take(count).mapIndexed { i, target -> CoachMarkController.Step(target, "Tip ${i + 1}") },
        )
        settle()
    }

    private fun message() = activity.findViewById<TextView>(R.id.coachMessage)
    private fun count() = activity.findViewById<TextView>(R.id.coachCount)
    private fun skip() = activity.findViewById<MaterialButton>(R.id.coachSkip)
    private fun next() = activity.findViewById<MaterialButton>(R.id.coachNext)

    private fun tap(button: MaterialButton) {
        button.performClick()
        settle()
    }

    @Test
    fun `a sequence counts its tips and offers Skip beside Next`() {
        show(3)

        assertEquals("Tip 1", message().text.toString())
        assertTrue(count().isShown)
        assertEquals("1 of 3", count().text.toString())
        assertTrue(skip().isShown)
        assertEquals("Skip", skip().text.toString())
        assertEquals("Next", next().text.toString())
    }

    @Test
    fun `a single tip has no count and only Got it`() {
        show(1)

        assertEquals("Tip 1", message().text.toString())
        assertFalse(count().isShown)
        assertFalse(skip().isShown)
        assertTrue(next().isShown)
        assertEquals("Got it", next().text.toString())
    }

    @Test
    fun `the last tip's Next reads Got it, and Got it ends the sequence as seen`() {
        show(3)

        tap(next())
        assertEquals("Tip 2", message().text.toString())
        assertEquals("2 of 3", count().text.toString())
        assertEquals("Next", next().text.toString())

        tap(next())
        assertEquals("Tip 3", message().text.toString())
        assertEquals("3 of 3", count().text.toString())
        assertEquals("Got it", next().text.toString())
        assertFalse("Skip has nothing left to skip", skip().isShown)
        assertFalse(CoachPrefs.hasSeen(activity, CoachPrefs.Screen.SWEEP_LATTICE))

        tap(next())
        assertNull("the overlay is gone", activity.findViewById(R.id.coachCard))
        assertTrue(CoachPrefs.hasSeen(activity, CoachPrefs.Screen.SWEEP_LATTICE))
    }

    @Test
    fun `Skip ends the sequence at once and marks the screen seen`() {
        show(3)

        tap(skip())

        assertNull("the overlay is gone", activity.findViewById(R.id.coachCard))
        assertTrue(CoachPrefs.hasSeen(activity, CoachPrefs.Screen.SWEEP_LATTICE))
        assertFalse("other screens keep their tips", CoachPrefs.hasSeen(activity, CoachPrefs.Screen.HOME))

        show(3)
        assertNull("a seen screen shows nothing", activity.findViewById(R.id.coachCard))
    }

    @Test
    fun `Skip is a quiet text button and Next the one filled button`() {
        show(2)

        val secondary = ContextCompat.getColor(activity, R.color.text_secondary)
        assertEquals(secondary, skip().textColors.defaultColor)
        assertEquals(
            "Skip has no fill",
            0,
            skip().backgroundTintList?.colorAt(enabled = true)?.let { it ushr 24 } ?: 0,
        )
        assertEquals(
            ContextCompat.getColor(activity, R.color.sky_primary),
            next().backgroundTintList?.colorAt(enabled = true),
        )
    }

    @Test
    fun `the tip is announced and both actions are labelled`() {
        show(2)

        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, message().accessibilityLiveRegion)
        assertNotEquals("", skip().text.toString())
        assertNotEquals("", next().text.toString())
        assertEquals("placed and visible", 1f, activity.findViewById<View>(R.id.coachCard).alpha)
    }

    @Test
    fun `the card sits beside its target, below one at the top of the screen`() {
        show(2)

        val card = activity.findViewById<View>(R.id.coachCard)
        val cardAt = IntArray(2).also { card.getLocationInWindow(it) }
        val targetAt = IntArray(2).also { targets[0].getLocationInWindow(it) }
        val density = activity.resources.displayMetrics.density
        // The ring's 6 dp pad, then the 12 dp gap the pointer sits in.
        val expected = targetAt[1] + targets[0].height + (6 * density).toInt() + (12 * density).toInt()
        assertEquals(expected, cardAt[1])
    }

    private fun ColorStateList.colorAt(enabled: Boolean): Int =
        getColorForState(intArrayOf(if (enabled) android.R.attr.state_enabled else -android.R.attr.state_enabled), 0)
}
