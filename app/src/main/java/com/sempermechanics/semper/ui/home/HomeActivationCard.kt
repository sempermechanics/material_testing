package com.sempermechanics.semper.ui.home

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.ActivationDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.ui.phones.PhonesActivity

/**
 * The card above Home's list for the account's licence and this phone
 * (ADR-033): the licence is there and this phone is not on it, so Activate;
 * every phone it covers is in use, so Your phones; or a new licence waits for
 * Activate. The licence reaches the account on the server, so the card only
 * ever says which phone it covers, never where to get one.
 *
 * Activate opens Your phones, which taps it on its ViewModel (ADR-016), so a
 * rotation cannot lose the answer. **Not now** hides the card until the offer
 * changes ([Offer.key]).
 */
internal class HomeActivationCard(
    private val card: View,
    private val text: TextView,
    hideButton: View,
    private val goButton: MaterialButton,
) {
    private var shown: Offer? = null

    init {
        hideButton.setOnClickListener {
            shown?.let { AppRemoteConfig.hideActivationCard(card.context, it.key) }
            card.isVisible = false
        }
        goButton.setOnClickListener {
            val offer = shown ?: return@setOnClickListener
            PhonesActivity.open(card.context, activate = offer.activate, upgrade = offer.kind == Kind.UPGRADE)
        }
    }

    /** Reads the cached config and shows the card, or hides it. */
    fun render() {
        val context = card.context
        val offer = offerOf(
            AppRemoteConfig.activation(context),
            AppRemoteConfig.pendingLicence(context),
            hidden = AppRemoteConfig.activationCardHidden(context),
        )
        shown = offer
        card.isVisible = offer != null
        if (offer == null) return
        text.setText(offer.kind.textRes)
        goButton.setText(offer.kind.actionRes)
    }

    /** What the card offers. */
    enum class Kind(@StringRes val textRes: Int, @StringRes val actionRes: Int) {
        UPGRADE(R.string.home_upgrade_text, R.string.phones_activate_upgrade),
        ACTIVATE(R.string.home_activate_text, R.string.phones_activate),
        FULL(R.string.home_activate_full_text, R.string.home_activate_phones),
    }

    /** An offer, and the [key] that hiding it stores: a changed licence or count offers it again. */
    data class Offer(val kind: Kind, val key: String) {
        /** The button taps Activate (else it opens Your phones to deactivate one). */
        val activate: Boolean get() = kind != Kind.FULL
    }

    companion object {
        /**
         * The card for [activation] and [pending], or null: nothing to offer,
         * or the user hid this very offer ([hidden]).
         */
        fun offerOf(
            activation: AppRemoteConfig.Activation,
            pending: AppRemoteConfig.PendingLicence?,
            hidden: String,
        ): Offer? {
            val offer = when {
                pending != null -> Offer(Kind.UPGRADE, "upgrade:${pending.licenseId}")
                activation.state == ActivationDto.STATE_AVAILABLE ->
                    Offer(Kind.ACTIVATE, "activate:${activation.maxDevices}")
                activation.state == ActivationDto.STATE_FULL ->
                    Offer(Kind.FULL, "full:${activation.maxDevices}")
                else -> null
            }
            return offer?.takeIf { it.key != hidden }
        }
    }
}
