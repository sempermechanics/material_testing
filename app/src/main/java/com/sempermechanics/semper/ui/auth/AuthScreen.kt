package com.sempermechanics.semper.ui.auth

import com.google.firebase.auth.MultiFactorResolver

/**
 * Which form [AuthActivity] shows. One value in place of the separate state
 * it replaces (the form's [AuthMode], an open TOTP challenge, and the reset
 * link's code), so the screen cannot be in two of them at once.
 */
sealed interface AuthScreen {

    /** What the form is for: its subtitle, fields and main action. */
    val mode: AuthMode

    /** The first-factor form: sign in, create an account, or re-authenticate. */
    data class Form(override val mode: AuthMode) : AuthScreen

    /** The new-password form from a verified reset link, its [oobCode] proving it. */
    data class Reset(val oobCode: String) : AuthScreen {
        override val mode: AuthMode get() = AuthMode.RESET_PASSWORD
    }

    /**
     * The authenticator-code form, after a first factor Firebase answered
     * with a TOTP challenge. [returnTo] is the form it came from: a re-auth
     * answers its caller once the code is accepted, a sign-in routes on.
     */
    data class Totp(
        val resolver: MultiFactorResolver,
        val enrollmentId: String,
        val returnTo: AuthMode,
    ) : AuthScreen {
        override val mode: AuthMode get() = returnTo
    }
}
