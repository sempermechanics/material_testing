package com.sempermechanics.semper.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.AccountCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home's one-time beta / data-use notice. Each account acks once; with no
 * account signed in the phone acks once, so the notice does not come back on
 * every launch (it did: the ack was keyed by a uid that was null).
 */
@RunWith(RobolectricTestRunner::class)
class BetaNoticeAckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun signedOut() = AccountCache.clear(context)

    @Test
    fun `a signed-out ack sticks across launches and sign-out`() {
        assertFalse(AccountCache.hasAckedBetaNotice(context))

        AccountCache.setBetaNoticeAcked(context)
        assertTrue(AccountCache.hasAckedBetaNotice(context))

        // Sign-out wipes the session prefs; the ack lives outside them.
        AccountCache.clear(context)
        assertTrue(AccountCache.hasAckedBetaNotice(context))
    }

    @Test
    fun `a signed-out ack does not stand in for an account's`() {
        AccountCache.setBetaNoticeAcked(context)

        AccountCache.saveIdentity(context, "uid-a", "a@example.com")
        assertFalse(AccountCache.hasAckedBetaNotice(context))
    }

    @Test
    fun `each account acks once and keeps it across sign-out`() {
        AccountCache.saveIdentity(context, "uid-a", "a@example.com")
        AccountCache.setBetaNoticeAcked(context)
        assertTrue(AccountCache.hasAckedBetaNotice(context))

        AccountCache.clear(context)
        assertFalse("an account's ack is not the phone's", AccountCache.hasAckedBetaNotice(context))

        AccountCache.saveIdentity(context, "uid-b", "b@example.com")
        assertFalse(AccountCache.hasAckedBetaNotice(context))

        AccountCache.saveIdentity(context, "uid-a", "a@example.com")
        assertTrue(AccountCache.hasAckedBetaNotice(context))
    }
}
