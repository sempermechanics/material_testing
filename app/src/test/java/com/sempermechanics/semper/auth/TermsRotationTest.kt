package com.sempermechanics.semper.auth

import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LegalTerms
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.ui.auth.TermsActivity
import com.sempermechanics.semper.ui.auth.TermsViewModel
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.home.HomeActivity
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * Agreeing to the Terms runs in [TermsViewModel]: a rotation while the
 * backend records it no longer cancels it, and the gate rebuilt in its place
 * waits on it and then moves on (or says why not), once.
 */
@RunWith(RobolectricTestRunner::class)
class TermsRotationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val built = mutableListOf<ActivityController<TermsActivity>>()

    @Before
    fun freshDevice() {
        AccountCache.clear(context)
        SignOutRun.resetForTest()
    }

    @After
    fun tearDown() {
        built.forEach { runCatching { it.pause().stop().destroy() } }
        SignOutRun.resetForTest()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun gate(): ActivityController<TermsActivity> {
        val intent = TermsActivity.intent(context, HomeActivity::class.java)
        return Robolectric.buildActivity(TermsActivity::class.java, intent).setup().also {
            built += it
            idle()
        }
    }

    private fun viewModelOf(activity: TermsActivity) = ViewModelProvider(activity)[TermsViewModel::class.java]

    /** Ticks the required box and taps Agree, with the improvement box at [consent]. */
    private fun agree(activity: TermsActivity, consent: Boolean = true) {
        activity.findViewById<CheckBox>(R.id.cbImprovementConsent).isChecked = consent
        activity.findViewById<CheckBox>(R.id.cbAgreeTerms).isChecked = true
        activity.findViewById<Button>(R.id.btnAgree).performClick()
        idle()
    }

    @Test
    fun `an acceptance answering across a rotation moves on from the new gate`() {
        val controller = gate()
        val vm = viewModelOf(controller.get())
        val answer = CompletableDeferred<Result<Unit>>()
        val asked = mutableListOf<Pair<String, Boolean>>()
        vm.accept = { version, consent ->
            asked += version to consent
            answer.await()
        }
        agree(controller.get(), consent = false)

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the acceptance's view model outlives the gate", vm, viewModelOf(rebuilt))
        assertFalse("the new gate waits on it", rebuilt.findViewById<Button>(R.id.btnAgree).isEnabled)
        assertNull(shadowOf(rebuilt).nextStartedActivity)

        answer.complete(Result.success(Unit))
        idle()

        assertEquals(listOf(LegalTerms.requiredVersion(context) to false), asked)
        assertTrue("the new gate moves on", rebuilt.isFinishing)
        assertEquals(HomeActivity::class.java.name, shadowOf(rebuilt).nextStartedActivity.component?.className)
    }

    @Test
    fun `a refused acceptance is said once on the gate that is there, which stays`() {
        val controller = gate()
        val vm = viewModelOf(controller.get())
        val answer = CompletableDeferred<Result<Unit>>()
        vm.accept = { _, _ -> answer.await() }
        agree(controller.get())

        val rebuilt = controller.recreate().get()
        idle()
        ShadowToast.reset()
        answer.complete(Result.failure(IllegalStateException("Could not record your acceptance.")))
        idle()

        assertEquals("Could not record your acceptance.", ShadowToast.getTextOfLatestToast())
        assertFalse(rebuilt.isFinishing)
        assertTrue("Agree is usable again", rebuilt.findViewById<Button>(R.id.btnAgree).isEnabled)

        ShadowToast.reset()
        controller.recreate()
        idle()
        assertEquals("not said again after the next rotation", 0, ShadowToast.shownToastCount())
    }
}
