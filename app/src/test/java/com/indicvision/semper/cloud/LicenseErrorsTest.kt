package com.indicvision.semper.cloud

import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.LicenseErrors
import com.indicvision.semper.data.net.ApiErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicenseErrorsTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `device mismatch maps to the restore-before-bind message`() {
        val msg = LicenseErrors.restoreMessage(
            ctx,
            """{"detail":"${ApiErrors.LICENSE_DEVICE_MISMATCH}"}""",
        )
        assertTrue(msg.contains("bound"))
        assertFalse(msg.contains(ApiErrors.LICENSE_DEVICE_MISMATCH))
    }

    @Test
    fun `demo feature refusal maps to the demo-mode restore message`() {
        val msg = LicenseErrors.restoreMessage(ctx, ApiErrors.FEATURE_NOT_LICENSED)
        assertEquals(ctx.getString(com.indicvision.semper.R.string.restore_not_licensed), msg)
        assertTrue(msg.contains("demo"))
    }

    @Test
    fun `demo feature refusal with the backend's readable suffix still maps by code`() {
        // The backend sends "feature_not_licensed: <sentence>" so pre-licensing
        // builds, which print the raw detail, show something readable.
        val body = """{"detail":"${ApiErrors.FEATURE_NOT_LICENSED}: Restore isn't available in demo mode."}"""
        val msg = LicenseErrors.restoreMessage(ctx, body)
        assertEquals(ctx.getString(com.indicvision.semper.R.string.restore_not_licensed), msg)
    }

    @Test
    fun `unknown detail stays formatted rather than blank`() {
        val msg = LicenseErrors.restoreMessage(ctx, "something_else")
        assertEquals(
            ctx.getString(com.indicvision.semper.R.string.restore_failed_fmt, "something_else"),
            msg,
        )
    }
}
