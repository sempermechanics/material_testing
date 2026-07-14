package com.rafad.indicvisiondic.data.net

import android.content.Context
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.ui.auth.GoogleSignInHelper

/**
 * Single source of a currently-valid Google ID token. Returns the cached token
 * while it is still valid, otherwise attempts a silent (no-UI) refresh from
 * Google Identity. Returns null when a fresh token would require user
 * interaction — callers then defer (e.g. WorkManager retry) or route to sign-in.
 */
object TokenProvider {

    suspend fun usableIdToken(context: Context): String? {
        TokenStore.validIdToken(context)?.let { return it }
        val fresh = GoogleSignInHelper.getIdTokenSilent(context, BuildConfig.GOOGLE_WEB_CLIENT_ID)
        if (fresh != null) TokenStore.saveToken(context, fresh)
        return fresh
    }
}
