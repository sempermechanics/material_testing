package com.sempermechanics.semper.data.policy

import java.util.Locale

/**
 * What an organisation's device management (EMM) set for this app, through
 * Android managed configurations (`res/xml/app_restrictions.xml`). Every value
 * has the default an unmanaged phone gets, so an unmanaged phone behaves as
 * if none of this existed.
 */
data class ManagedConfig(
    /**
     * The organisation's email domain (`example.edu`), or null. Google
     * sign-in offers only that domain's accounts (its hosted-domain filter).
     * A hint for the account picker, not an access control: the backend's
     * roster decides who is licensed.
     */
    val accountEmailDomain: String? = null,
    /** False: cloud backup is off on this phone (no backup screens, no uploads, no restores). */
    val cloudBackupAllowed: Boolean = true,
    /** True: uploads wait for an unmetered network whatever the user's Wi-Fi-only switch says. */
    val backupOnUnmeteredOnly: Boolean = false,
) {
    companion object {
        /** An unmanaged phone. */
        val UNMANAGED = ManagedConfig()

        const val KEY_ACCOUNT_EMAIL_DOMAIN = "accountEmailDomain"
        const val KEY_CLOUD_BACKUP_ALLOWED = "cloudBackupAllowed"
        const val KEY_BACKUP_ON_UNMETERED_ONLY = "backupOnUnmeteredOnly"

        private const val MAX_DOMAIN_LENGTH = 253
        private val DOMAIN = Regex("^(?=.{1,$MAX_DOMAIN_LENGTH}$)([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$")

        /**
         * The config in [values] (a restrictions `Bundle` read as a map). A
         * value of the wrong type, or a string that is not a plausible domain,
         * reads as unset: a typo in the EMM console must not lock
         * the phone out of sign-in or turn backup off.
         */
        fun from(values: Map<String, Any?>): ManagedConfig = ManagedConfig(
            accountEmailDomain = (values[KEY_ACCOUNT_EMAIL_DOMAIN] as? String)
                ?.trim()?.removePrefix("@")?.lowercase(Locale.ROOT)
                ?.takeIf { DOMAIN.matches(it) },
            cloudBackupAllowed = values[KEY_CLOUD_BACKUP_ALLOWED] as? Boolean ?: true,
            backupOnUnmeteredOnly = values[KEY_BACKUP_ON_UNMETERED_ONLY] as? Boolean ?: false,
        )
    }
}
