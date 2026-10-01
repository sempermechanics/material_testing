package com.indicvision.semper.ui.common

import android.app.Application
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Each [ToastPlacement] draws the pill the matching [CrispToast] call drew:
 * the same gravity, the same layout, held for the same time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToastPlacementTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var overlay: FrameLayout

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
        overlay = FrameLayout(activity)
        activity.setContentView(overlay)
    }

    private fun content(): ViewGroup = activity.findViewById(android.R.id.content)

    /** The pill under [root], found by its text view. */
    private fun pill(root: ViewGroup): View? =
        (0 until root.childCount).map { root.getChildAt(it) }
            .firstOrNull { it.findViewById<TextView>(R.id.tvToast) != null }

    private fun gravity(pill: View) = (pill.layoutParams as FrameLayout.LayoutParams).gravity

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `bottom and top sit where show and show fromTop put them`() {
        CrispToast.show(activity, "Saved", ToastPlacement.BOTTOM)
        assertEquals(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, gravity(pill(content())!!))

        CrispToast.show(activity, "Pick frames", ToastPlacement.TOP, overlayRoot = overlay)
        val top = pill(overlay)!!
        assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, gravity(top))
        assertEquals("Pick frames", top.findViewById<TextView>(R.id.tvToast).text.toString())
    }

    @Test
    fun `the hold is the pill's own short or long time, or the one given`() {
        CrispToast.show(activity, "Short", ToastPlacement.BOTTOM)
        idleFor(1999)
        assertNotNull(pill(content()))
        idleFor(2)
        assertNull(pill(content()))

        CrispToast.show(activity, "Long", ToastPlacement.TOP, hold = ToastHold.Long)
        idleFor(3499)
        assertNotNull(pill(content()))
        idleFor(2)
        assertNull(pill(content()))

        CrispToast.show(activity, "Tip", ToastPlacement.TOP, overlayRoot = overlay, hold = ToastHold.Millis(500))
        idleFor(501)
        assertNull(pill(overlay))
    }

    @Test
    fun `prominent is the larger centred pill, on the given root or the content`() {
        CrispToast.show(
            activity,
            "Pick a reference",
            ToastPlacement.CENTER_PROMINENT,
            overlayRoot = overlay,
            hold = ToastHold.Millis(1200),
        )
        val onOverlay = pill(overlay)!!
        assertEquals(Gravity.CENTER, gravity(onOverlay))
        idleFor(1201)
        assertNull(pill(overlay))

        CrispToast.show(activity, "Pick a reference", ToastPlacement.CENTER_PROMINENT)
        assertEquals(Gravity.CENTER, gravity(pill(content())!!))
        idleFor(2001)
        assertNull(pill(content()))
    }

    @Test
    fun `a context that is not an Activity shows nothing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        CrispToast.show(app, "Nowhere", ToastPlacement.CENTER_PROMINENT)
        CrispToast.show(app, "Nowhere", ToastPlacement.BOTTOM)
        assertNull(pill(content()))
    }

    @Test
    fun `hold times`() {
        assertEquals(2000L, holdMs(ToastHold.Short))
        assertEquals(3500L, holdMs(ToastHold.Long))
        assertEquals(750L, holdMs(ToastHold.Millis(750)))
    }
}
