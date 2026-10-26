package com.sempermechanics.semper.data.account

import androidx.annotation.StringRes
import com.sempermechanics.semper.R

/**
 * How a sign-in started, as the `method` of its `sign_in` / `sign_in_failed`
 * analytics events. [analyticsName] is what dashboards already group by.
 */
internal enum class SignInMethod(val analyticsName: String) {
    GOOGLE("google"),
    PASSWORD("password"),
    PASSWORD_SIGN_UP("password_signup"),
    TOTP("totp"),
    EMAIL_LINK("email_link"),
    ;

    /** What a rejected credential means for this method. */
    @get:StringRes
    val wrongCredentials: Int
        get() = if (this == TOTP) R.string.auth_wrong_totp_code else R.string.auth_wrong_credentials
}
