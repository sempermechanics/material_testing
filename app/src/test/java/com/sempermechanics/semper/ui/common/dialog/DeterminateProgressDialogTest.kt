package com.sempermechanics.semper.ui.common.dialog

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.dynamicanimation.animation.SpringFrames
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sempermechanics.semper.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The bar springs to each value while the dialog shows, and a dismissed
 * dialog's spring ends on the next frames instead of running on through its
 * settle, holding the Activity (TD-200). Nothing in the dialog does that:
 * dismissing detaches the bar, and a detached view jumps its drawables to
 * their current state, which asks Material's spring to end on its next frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeterminateProgressDialogTest {

    private val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.Theme_Semper) }
        .setup()

    @After
    fun tearDown() {
        controller.close()
        SpringFrames.endAll()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun View.bar(): LinearProgressIndicator? = when (this) {
        is LinearProgressIndicator -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).bar() }
        else -> null
    }

    @Test
    fun `a dismissed dialog's spring ends on the next frames`() {
        val dialog = DeterminateProgressDialog(controller.get(), "Exporting")
        dialog.show()
        idle()
        val bar = checkNotNull(ShadowDialog.getLatestDialog().window?.decorView?.bar())
        val drawable = checkNotNull(bar.progressDrawable)
        // The first value turns the bar determinate; idling settles it.
        dialog.update(30)
        idle()

        // Not idled: the spring is still moving when the dialog goes, as when
        // a job finishes right after its last progress.
        dialog.update(60)
        assertEquals(60, bar.progress)
        assertTrue("the bar springs while the dialog shows", SpringFrames.running(drawable))

        dialog.dismiss()
        SpringFrames.step()
        assertFalse("a spring outlived the dialog", SpringFrames.running(drawable))
    }
}
