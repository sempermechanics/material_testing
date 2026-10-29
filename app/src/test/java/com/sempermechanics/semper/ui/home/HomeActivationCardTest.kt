package com.sempermechanics.semper.ui.home

import com.sempermechanics.semper.data.net.ActivationDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which card Home offers for the licence and this phone ([HomeActivationCard.offerOf], ADR-033). */
class HomeActivationCardTest {

    private fun activation(state: String, max: Int = 2) =
        AppRemoteConfig.Activation(state, devicesActive = 1, maxDevices = max, pool = false, model = "activation")

    private fun offer(state: String, pending: AppRemoteConfig.PendingLicence? = null, hidden: String = "") =
        HomeActivationCard.offerOf(activation(state), pending, hidden)

    @Test
    fun `a free slot offers Activate, a full licence Your phones`() {
        val activate = offer(ActivationDto.STATE_AVAILABLE)
        assertEquals(HomeActivationCard.Kind.ACTIVATE, activate?.kind)
        assertTrue(activate!!.activate)

        val full = offer(ActivationDto.STATE_FULL)
        assertEquals(HomeActivationCard.Kind.FULL, full?.kind)
        assertFalse(full!!.activate)
    }

    @Test
    fun `a new licence waiting comes first, even on an active phone`() {
        val pending = AppRemoteConfig.PendingLicence("new-1", "SEM-AB", AppRemoteConfig.NO_INSTANT)
        val upgrade = offer(ActivationDto.STATE_ACTIVE, pending)
        assertEquals(HomeActivationCard.Kind.UPGRADE, upgrade?.kind)
        assertTrue(upgrade!!.activate)
    }

    @Test
    fun `nothing to offer on an active phone, Demo, or a floating seat`() {
        for (state in listOf(ActivationDto.STATE_ACTIVE, ActivationDto.STATE_NONE, ActivationDto.STATE_FLOATING)) {
            assertNull(state, offer(state))
        }
    }

    @Test
    fun `a hidden offer stays hidden until the licence or its count changes`() {
        val key = offer(ActivationDto.STATE_AVAILABLE)!!.key
        assertNull(offer(ActivationDto.STATE_AVAILABLE, hidden = key))
        // A bigger count, or the same count full, is a new offer.
        assertEquals(
            HomeActivationCard.Kind.ACTIVATE,
            HomeActivationCard.offerOf(activation(ActivationDto.STATE_AVAILABLE, max = 3), null, key)?.kind,
        )
        assertEquals(HomeActivationCard.Kind.FULL, offer(ActivationDto.STATE_FULL, hidden = key)?.kind)
    }
}
