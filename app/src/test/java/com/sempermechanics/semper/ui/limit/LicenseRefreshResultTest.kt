package com.sempermechanics.semper.ui.limit

import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.entitlement.ConfigFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a "Refresh licence" tap says ([LicenseRefresh.resultOf]) and when it
 * leaves a retry queued ([LicenseRefresh.queuesRetry]).
 */
class LicenseRefreshResultTest {

    private fun state(licensed: Boolean, kind: String = "") =
        LicenseState(licensed = licensed, kind = kind, backup = licensed, share = licensed, cap = 25)

    @Test
    fun `an answer that licenses on an institution licence says institution seat`() {
        assertEquals(
            LicenseRefreshResult.LICENSED_INSTITUTION,
            LicenseRefresh.resultOf(ConfigFetch.ANSWERED, state(licensed = true, kind = "institution")),
        )
    }

    @Test
    fun `an answer that licenses on the account's own licence says Semper licence`() {
        assertEquals(
            LicenseRefreshResult.LICENSED_INDIVIDUAL,
            LicenseRefresh.resultOf(ConfigFetch.ANSWERED, state(licensed = true, kind = "individual")),
        )
        // A backend that predates licenseKind sends nothing: still licensed.
        assertEquals(
            LicenseRefreshResult.LICENSED_INDIVIDUAL,
            LicenseRefresh.resultOf(ConfigFetch.ANSWERED, state(licensed = true)),
        )
    }

    @Test
    fun `an answer that leaves the account on demo says so, whatever licence it names`() {
        assertEquals(LicenseRefreshResult.DEMO, LicenseRefresh.resultOf(ConfigFetch.ANSWERED, state(false)))
        // An institution member without a seat is on demo until one is taken.
        assertEquals(
            LicenseRefreshResult.DEMO,
            LicenseRefresh.resultOf(ConfigFetch.ANSWERED, state(licensed = false, kind = "institution")),
        )
    }

    @Test
    fun `a licence this phone is not on says activate, or that its phones are full`() {
        val available = state(false).copy(activation = "available")
        val full = state(false).copy(activation = "full")
        assertEquals(LicenseRefreshResult.NOT_ACTIVATED, LicenseRefresh.resultOf(ConfigFetch.ANSWERED, available))
        assertEquals(LicenseRefreshResult.PHONES_FULL, LicenseRefresh.resultOf(ConfigFetch.ANSWERED, full))
        // Licensed here: the state is "active", and the old lines hold.
        assertEquals(
            LicenseRefreshResult.LICENSED_INDIVIDUAL,
            LicenseRefresh.resultOf(ConfigFetch.ANSWERED, state(true).copy(activation = "active")),
        )
    }

    @Test
    fun `a new licence waiting is said first, licensed or not`() {
        for (licensed in listOf(true, false)) {
            assertEquals(
                LicenseRefreshResult.UPGRADE_READY,
                LicenseRefresh.resultOf(ConfigFetch.ANSWERED, state(licensed).copy(upgrade = true)),
            )
        }
        assertEquals(
            LicenseRefreshResult.UNREACHABLE,
            LicenseRefresh.resultOf(ConfigFetch.FAILED, state(true).copy(upgrade = true)),
        )
    }

    @Test
    fun `no answer is couldn't reach, never demo or licensed from a stale cache`() {
        for (fetch in listOf(ConfigFetch.FAILED, ConfigFetch.NOT_MADE)) {
            assertEquals(fetch.name, LicenseRefreshResult.UNREACHABLE, LicenseRefresh.resultOf(fetch, state(false)))
            assertEquals(
                fetch.name,
                LicenseRefreshResult.UNREACHABLE,
                LicenseRefresh.resultOf(fetch, state(licensed = true, kind = "institution")),
            )
        }
    }

    @Test
    fun `only an answered fetch leaves no retry queued`() {
        assertFalse(LicenseRefresh.queuesRetry(ConfigFetch.ANSWERED))
        assertTrue(LicenseRefresh.queuesRetry(ConfigFetch.FAILED))
        assertTrue(LicenseRefresh.queuesRetry(ConfigFetch.NOT_MADE))
    }

    @Test
    fun `each result has its own line`() {
        assertEquals(R.string.license_refresh_institution, LicenseRefreshResult.LICENSED_INSTITUTION.messageRes)
        assertEquals(R.string.license_refresh_individual, LicenseRefreshResult.LICENSED_INDIVIDUAL.messageRes)
        assertEquals(R.string.license_refresh_demo, LicenseRefreshResult.DEMO.messageRes)
        assertEquals(R.string.license_refresh_unreachable, LicenseRefreshResult.UNREACHABLE.messageRes)
        assertEquals(R.string.license_refresh_not_activated, LicenseRefreshResult.NOT_ACTIVATED.messageRes)
        assertEquals(R.string.license_refresh_phones_full, LicenseRefreshResult.PHONES_FULL.messageRes)
        assertEquals(R.string.license_refresh_upgrade_ready, LicenseRefreshResult.UPGRADE_READY.messageRes)
        assertEquals(
            LicenseRefreshResult.entries.size,
            LicenseRefreshResult.entries.map { it.messageRes }.toSet().size,
        )
    }
}
