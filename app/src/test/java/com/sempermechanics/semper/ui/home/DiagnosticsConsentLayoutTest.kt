package com.sempermechanics.semper.ui.home

import android.app.Application
import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AccountCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The first-run diagnostics question on a 360 dp phone (1080 x 2400 at 480 dpi,
 * as the readiness emulator ran it). At font scale 2.0 the stock stacked
 * button bar left one of the two choices cut to 26 dp under the other, first
 * 'Not now', then, after the order was swapped, 'Send reports' (readiness
 * AE1-103). Both choices must be laid out whole, on screen, at the same size,
 * at font scale 1 and 2; the body may scroll.
 *
 * The prompt is shown as Home shows it ([FirstRunPrompts.show]), from a plain
 * themed Activity under a bare Application: a whole Home (Firebase, WorkManager)
 * drawn with native graphics ended the unit-test JVM in the class after this one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-port-xxhdpi")
class DiagnosticsConsentLayoutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<AppCompatActivity>>()

    @Before
    fun setUp() {
        AccountCache.setBetaNoticeAcked(context)
    }

    @After
    fun tearDown() {
        screens.forEach { it.close() }
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun consentAt(fontScale: Float): Dialog {
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .also { screens += it }
        val activity = controller.get()
        val fab = View(activity)
        activity.setContentView(fab)
        FirstRunPrompts(activity).show(fab)
        shadowOf(activity.mainLooper).idle()
        return ShadowDialog.getShownDialogs().last { it.isShowing }
    }

    private fun Dialog.choice(text: Int): Button {
        val label = context.getString(text)
        val found = mutableListOf<Button>()
        fun walk(v: View) {
            if (v is Button && v.isShown && v.text.toString() == label) found += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(window!!.decorView)
        return found.single()
    }

    /** Whole: its full box is on screen, and its label is laid out with nothing cut or ellipsized. */
    private fun assertWhole(button: Button, name: String) {
        val visible = Rect()
        assertTrue("$name is not on screen", button.getGlobalVisibleRect(visible))
        assertEquals("$name visible height of ${button.height}px", button.height, visible.height())
        assertEquals("$name visible width of ${button.width}px", button.width, visible.width())
        val layout = button.layout
        val lines = layout.lineCount
        assertTrue("$name '${button.text}' needs $lines lines of ${button.maxLines}", lines <= button.maxLines)
        assertEquals(
            "$name ellipsized: $lines lines, ${button.width}px wide, ${button.textSize}px text",
            0,
            layout.getEllipsisCount(lines - 1),
        )
        val textHeight = layout.height + button.totalPaddingTop + button.totalPaddingBottom
        assertTrue("$name is ${button.height}px high, its text $textHeight px", button.height >= textHeight)
        val room = button.width - button.totalPaddingLeft - button.totalPaddingRight
        val widest = (0 until lines).maxOf { layout.getLineWidth(it) }
        assertTrue("$name line is ${widest}px in ${room}px", widest <= room)
    }

    private fun assertBothWhole(fontScale: Float) {
        val dialog = consentAt(fontScale)
        val title = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
        assertEquals(context.getString(R.string.diagnostics_prompt_title), title.text.toString())
        val decline = dialog.choice(R.string.diagnostics_prompt_decline)
        val accept = dialog.choice(R.string.diagnostics_prompt_accept)
        assertWhole(decline, "fs $fontScale 'Not now'")
        assertWhole(accept, "fs $fontScale 'Send reports'")
        // Equal weight: the same size, neither above the fold of the other.
        assertEquals("fs $fontScale button widths", decline.width, accept.width)
        assertEquals("fs $fontScale button heights", decline.height, accept.height)
    }

    @Test
    fun `both choices are whole and the same size at font scale 2`() = assertBothWhole(2f)

    @Test
    fun `both choices are whole and the same size at font scale 1`() = assertBothWhole(1f)
}
