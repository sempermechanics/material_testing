package com.sempermechanics.semper.ui.common.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.sempermechanics.semper.data.account.DevAuth
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.auth.AuthActivity
import com.sempermechanics.semper.ui.auth.SplashActivity
import java.util.UUID

/**
 * Returns to the sign-in screen and clears the task behind it, so a signed-out
 * user cannot back into Home. Shared by every screen that can end a session
 * (sign-out, account deletion, a declined Terms gate, a lost approval, the
 * background status check), so all of them honour the emulator bypass.
 */
object AuthRoute {

    /** Sign in again from [activity], which finishes; [message] is shown on the sign-in screen. */
    fun toSignIn(activity: Activity, message: String? = null) {
        activity.startActivity(signInIntent(activity, message))
        activity.finish()
    }

    /**
     * The intent behind [toSignIn], for a caller with no Activity to finish
     * (the background status check starts it from the application context).
     *
     * Under the emulator dev bypass ([DevAuth.active]) there is nothing to sign
     * in to — the cloud is off, so the sign-in screen could never finish — and
     * the route goes back through the splash, which re-seeds the dev session
     * and returns Home. [message] has no screen to show on there and is dropped.
     */
    fun signInIntent(context: Context, message: String? = null, devBypass: Boolean = DevAuth.active): Intent {
        val target = if (devBypass) SplashActivity::class.java else AuthActivity::class.java
        return Intent(context, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (!devBypass && message != null) putMessage(this, message)
        }
    }

    /**
     * Puts [message] on [intent] for the sign-in screen, marked as this process's own.
     * AuthActivity is exported (App Links), so another app can start it with any
     * extras; it shows only a message that carries this mark ([messageOf]).
     */
    fun putMessage(intent: Intent, message: String) {
        intent.putExtra(IntentKeys.ROUTING_ERROR, message)
        intent.putExtra(IntentKeys.ROUTING_TOKEN, processToken)
    }

    /** The routing message on [intent] if this process put it there ([putMessage]), else null. */
    fun messageOf(intent: Intent): String? =
        intent.getStringExtra(IntentKeys.ROUTING_ERROR)
            ?.takeIf { intent.getStringExtra(IntentKeys.ROUTING_TOKEN) == processToken }

    /** Random per process: an app outside this one cannot know it. */
    private val processToken: String = UUID.randomUUID().toString()
}
