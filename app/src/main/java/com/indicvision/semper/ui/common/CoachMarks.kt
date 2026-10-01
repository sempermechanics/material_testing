package com.indicvision.semper.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import com.indicvision.semper.data.prefs.CoachPrefs

/**
 * A one-step coach for [screen]: [message] pointed at [target], shown only if
 * [screen] has not been seen ([CoachMarkController.maybeShow]).
 */
fun CoachMarkController.maybeShowOne(
    screen: CoachPrefs.Screen,
    target: View,
    message: String,
    @DrawableRes illustration: Int? = null,
    overlayParent: ViewGroup? = null,
) {
    maybeShow(
        screen,
        listOf(CoachMarkController.Step(target, message, illustration = illustration)),
        overlayParent,
    )
}
