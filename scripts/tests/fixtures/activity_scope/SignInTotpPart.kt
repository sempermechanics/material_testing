package com.example.ui.auth

// A part of the sign-in screen, handed the Activity: its runAuth lambda runs on
// that Activity's scope too.
internal class SignInTotpPart(private val activity: SignInThroughRunAuthActivity) {
    fun submit(code: String) {
        activity.runAuth { activity.repo.completeTotpChallenge(code) }
    }
}
