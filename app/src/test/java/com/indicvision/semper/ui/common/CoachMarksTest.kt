package com.indicvision.semper.ui.common

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.data.prefs.CoachPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A one-step coach: shown once per screen, with the Done button and no Skip. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoachMarksTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var content: FrameLayout
    private lateinit var target: View

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
        content = FrameLayout(activity)
        target = View(activity)
        content.addView(target, FrameLayout.LayoutParams(100, 100))
        activity.setContentView(content)
    }

    private fun texts(root: View): List<String> = when (root) {
        is TextView -> listOf(root.text.toString()).filter { root.isShown }
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `an unseen screen shows its one step`() {
        CoachMarkController(activity).maybeShowOne(CoachPrefs.Screen.HOME, target, "Tap + to start")

        val shown = texts(activity.findViewById(android.R.id.content))
        assertTrue(shown.toString(), "Tap + to start" in shown)
        assertTrue(activity.getString(R.string.coach_done) in shown)
        assertFalse(activity.getString(R.string.coach_skip) in shown)
    }

    @Test
    fun `Done marks it seen, so it does not come back`() {
        val coach = CoachMarkController(activity)
        coach.maybeShowOne(CoachPrefs.Screen.HOME, target, "Tap + to start", overlayParent = content)
        coach.dismiss(markSeen = true)
        assertTrue(CoachPrefs.hasSeen(activity, CoachPrefs.Screen.HOME))
        val before = content.childCount

        CoachMarkController(activity)
            .maybeShowOne(CoachPrefs.Screen.HOME, target, "Tap + to start", overlayParent = content)

        assertEquals(before, content.childCount)
    }
}
