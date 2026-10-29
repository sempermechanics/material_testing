package com.sempermechanics.semper.cloud

import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.account.LicenseErrors
import com.sempermechanics.semper.data.net.ApiErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
    fun `a paused restore or download says paused, not demo and not a failure`() {
        val body = """{"detail":"${ApiErrors.FEATURE_PAUSED}"}"""
        val restorePaused = ctx.getString(com.sempermechanics.semper.R.string.restore_paused)
        val downloadPaused = ctx.getString(com.sempermechanics.semper.R.string.download_paused)
        assertEquals(restorePaused, LicenseErrors.restoreMessage(ctx, body))
        assertEquals(downloadPaused, LicenseErrors.downloadMessage(ctx, body))
    }

    @Test
    fun `every kill switch sentence has one shape and names its own feature`() {
        // "<Feature> is paused right now. Your analyses on this phone aren't affected."
        // (readiness AE3-108): a paused download no longer shows the restore sentence.
        val tail = " is paused right now. Your analyses on this phone aren't affected."
        assertEquals(
            "Restoring from the cloud$tail",
            ctx.getString(com.sempermechanics.semper.R.string.restore_paused),
        )
        assertEquals(
            "Downloading from the cloud$tail",
            ctx.getString(com.sempermechanics.semper.R.string.download_paused),
        )
        assertEquals("Sharing$tail", ctx.getString(com.sempermechanics.semper.R.string.share_paused))
    }

    @Test
    fun `demo feature refusal maps to the Demo restore message`() {
        val msg = LicenseErrors.restoreMessage(ctx, ApiErrors.FEATURE_NOT_LICENSED)
        assertEquals(ctx.getString(com.sempermechanics.semper.R.string.restore_not_licensed), msg)
        // The tier is "Demo", capital D (readiness ADDENDUM-3).
        assertTrue(msg.contains("on Demo"))
    }

    @Test
    fun `demo feature refusal with the backend's readable suffix still maps by code`() {
        // The backend sends "feature_not_licensed: <sentence>" so pre-licensing
        // builds, which print the raw detail, show something readable.
        val body = """{"detail":"${ApiErrors.FEATURE_NOT_LICENSED}: Restore isn't available on Demo."}"""
        val msg = LicenseErrors.restoreMessage(ctx, body)
        assertEquals(ctx.getString(com.sempermechanics.semper.R.string.restore_not_licensed), msg)
    }

    @Test
    fun `refused bundle download says demo mode, anything else keeps the connection hint`() {
        val refused = LicenseErrors.downloadMessage(
            ctx,
            """{"detail":"${ApiErrors.FEATURE_NOT_LICENSED}: Restore isn't available on Demo."}""",
        )
        assertEquals(ctx.getString(com.sempermechanics.semper.R.string.download_not_licensed), refused)
        assertEquals(
            ctx.getString(com.sempermechanics.semper.R.string.download_analysis_failed),
            LicenseErrors.downloadMessage(ctx, "HTTP 502: upstream request timeout"),
        )
        assertEquals(
            ctx.getString(com.sempermechanics.semper.R.string.download_analysis_failed),
            LicenseErrors.downloadMessage(ctx, null),
        )
    }

    @Test
    fun `a backup gone from Drive says so on restore and on download`() {
        val body = """{"detail":"${ApiErrors.DRIVE_FILE_GONE}"}"""
        val gone = ctx.getString(com.sempermechanics.semper.R.string.restore_backup_gone)
        assertEquals(gone, LicenseErrors.restoreMessage(ctx, body))
        assertEquals(gone, LicenseErrors.downloadMessage(ctx, body))
    }

    @Test
    fun `an unknown detail is the generic message, never the code`() {
        // The code is the server's, not words for the user (readiness AE1-009).
        val msg = LicenseErrors.restoreMessage(ctx, "something_else")
        assertEquals(ctx.getString(com.sempermechanics.semper.R.string.restore_failed_generic), msg)
    }
}
