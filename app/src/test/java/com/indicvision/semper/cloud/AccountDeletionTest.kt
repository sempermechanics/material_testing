package com.indicvision.semper.cloud

import com.indicvision.semper.data.CloudSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account deletion is the one flow that destroys data it cannot get back, so
 * its ordering is pinned here: the cloud first, and no local wipe until the
 * cloud copy is confirmed gone.
 */
class AccountDeletionTest {

    /** Records what ran, in order, so the sequence can be asserted. */
    private val steps = mutableListOf<String>()

    private fun run(
        cloudErased: Boolean,
        identityErased: Boolean,
    ): CloudSync.AccountDeletion = runBlocking {
        CloudSync.deleteAccount(
            eraseCloud = {
                steps.add("cloud")
                cloudErased
            },
            deleteIdentity = {
                steps.add("identity")
                identityErased
            },
            wipeLocal = { steps.add("local") },
            signOut = { steps.add("signOut") },
        )
    }

    @Test
    fun `everything gone reports DELETED`() {
        val result = run(cloudErased = true, identityErased = true)
        assertEquals(CloudSync.AccountDeletion.DELETED, result)
    }

    @Test
    fun `an unreachable cloud leaves local data alone`() {
        val result = run(cloudErased = false, identityErased = true)
        assertEquals(CloudSync.AccountDeletion.CLOUD_UNREACHABLE, result)
        assertFalse("local data must survive a failed cloud erase", steps.contains("local"))
        assertFalse("the session must continue after a failed erase", steps.contains("signOut"))
    }

    @Test
    fun `a surviving identity still wipes the device and ends the session`() {
        val result = run(cloudErased = true, identityErased = false)
        assertEquals(CloudSync.AccountDeletion.IDENTITY_KEPT, result)
        assertTrue(steps.contains("local"))
        assertTrue("data is gone, so the session must not continue", steps.contains("signOut"))
    }

    @Test
    fun `the cloud is erased before anything local is touched`() {
        run(cloudErased = true, identityErased = true)
        assertEquals(listOf("cloud", "identity", "local", "signOut"), steps)
    }

    @Test
    fun `the identity delete is attempted before the session is signed out`() {
        run(cloudErased = true, identityErased = true)
        assertTrue(
            "signing out first would strip the credential the delete needs",
            steps.indexOf("identity") < steps.indexOf("signOut"),
        )
    }
}
