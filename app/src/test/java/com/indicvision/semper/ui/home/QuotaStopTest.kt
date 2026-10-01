package com.indicvision.semper.ui.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.net.TokenStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home folds the phone's analysis count into the limit on every start and
 * refresh. It must not lift the stop the upload worker forces when the
 * backend refuses a backup for the account's limit: that used to vanish
 * before the user next tapped **+**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuotaStopTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        TokenStore.setQuota(context, used = 0, localCount = 0)
    }

    @Test
    fun `a stop the worker forced survives Home's refresh`() {
        assertFalse("precondition: well under any limit", TokenStore.isSessionLimitReached(context))
        TokenStore.setSessionLimitReached(context, true)

        QuotaStop.foldLocalCount(context, localCount = 1)

        assertTrue(TokenStore.isSessionLimitReached(context))
    }

    @Test
    fun `with no stop in force, the refresh forces none`() {
        QuotaStop.foldLocalCount(context, localCount = 1)
        assertFalse(TokenStore.isSessionLimitReached(context))
    }

    @Test
    fun `fresh server numbers still lift it`() {
        TokenStore.setSessionLimitReached(context, true)
        QuotaStop.foldLocalCount(context, localCount = 1)

        TokenStore.setQuota(context, used = 1, localCount = 1)

        assertFalse(TokenStore.isSessionLimitReached(context))
    }
}
