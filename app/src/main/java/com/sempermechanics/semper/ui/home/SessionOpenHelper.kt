package com.sempermechanics.semper.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.viewer.ViewerArgs

/**
 * Opens a [SessionRecord] in the result viewer or, for a sweep, the VSG
 * lattice. The extras are [ViewerArgs.of] the row, the one builder a run that
 * just saved its row opens with too, so the two cannot drift apart.
 */
object SessionOpenHelper {

    /**
     * Opens [session], or says why it cannot be opened when its frames are no
     * longer on this phone — a session deleted locally but kept in the cloud
     * still shows on both the Home list and the settings page.
     *
     * [hasLocalData] is the answer the list read off the main thread; a tap
     * never lists the session directory itself.
     */
    fun openOrExplain(activity: Activity, session: SessionRecord, hasLocalData: Boolean) {
        if (!hasLocalData) {
            Dialogs.info(activity, R.string.session_data_gone_title, R.string.session_data_gone_body)
            return
        }
        activity.startActivity(intentFor(activity, session))
    }

    fun intentFor(context: Context, session: SessionRecord): Intent = ViewerArgs.of(session).toIntent(context)
}
