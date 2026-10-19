package com.sempermechanics.semper.ui.viewer.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.policy.FeatureAccess
import com.sempermechanics.semper.fixtures.CleanAppState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The viewer's Share, greyed and never hidden: what it reads for this account
 * ([ShareCenter.access]) and what a tap on it says ([ShareCenter.blockedMessage]).
 */
@RunWith(RobolectricTestRunner::class)
class ShareAccessTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = AppRemoteConfig.clear(context)

    private fun config(mode: String, share: Boolean, killSwitches: List<String>? = null) =
        AppRemoteConfig.apply(
            context,
            AppConfigDto(mode = mode, shareEnabled = share, maxSessions = 25, killSwitches = killSwitches),
        )

    @Test
    fun `a licence with sharing opens Share`() {
        config("licensed", share = true)
        assertEquals(FeatureAccess.OPEN, ShareCenter.access(context))
        assertNull(ShareCenter.blockedMessage(FeatureAccess.OPEN))
    }

    @Test
    fun `demo is locked and says the licence note`() {
        config("demo", share = false)
        assertEquals(FeatureAccess.LOCKED, ShareCenter.access(context))
        assertEquals(R.string.license_lock_note, ShareCenter.blockedMessage(FeatureAccess.LOCKED))
    }

    @Test
    fun `the share kill switch pauses a licensed account, worded as temporarily unavailable`() {
        config("licensed", share = true, killSwitches = listOf("share"))
        assertEquals(FeatureAccess.PAUSED, ShareCenter.access(context))
        assertEquals(R.string.share_paused, ShareCenter.blockedMessage(FeatureAccess.PAUSED))
    }

    @Test
    fun `the kill switch on a demo account still reads as the licence lock`() {
        config("demo", share = false, killSwitches = listOf("share"))
        assertEquals(FeatureAccess.LOCKED, ShareCenter.access(context))
    }
}
