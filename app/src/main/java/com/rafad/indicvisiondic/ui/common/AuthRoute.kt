package com.rafad.indicvisiondic.ui.common

import android.app.Activity
import android.content.Intent
import com.rafad.indicvisiondic.data.DevAuth
import com.rafad.indicvisiondic.ui.auth.AuthActivity
import com.rafad.indicvisiondic.ui.auth.SplashActivity

/**
 * Returns to the sign-in screen and clears the task behind it, so a signed-out
 * user cannot back into Home. Shared by every screen that can end a session.
 */
object AuthRoute {

    fun toSignIn(activity: Activity) {
        // Under the emulator dev bypass there is nothing to sign in to: go back
        // through the splash, which re-seeds the dev session and returns Home.
        val target = if (DevAuth.active) SplashActivity::class.java else AuthActivity::class.java
        val intent = Intent(activity, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        activity.startActivity(intent)
        activity.finish()
    }
}
