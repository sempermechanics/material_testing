package com.sempermechanics.semper.ext

import androidx.annotation.VisibleForTesting

/**
 * The one instance of each extension point a product build installs
 * (ADR-025).
 * Each starts as the default that does what Semper does today, and Semper
 * installs nothing. The other points (wizard, CSV, report, viewer, share, new
 * analysis) join here as they land.
 */
object AppExtensions {

    /** What a product adds to a saved analysis; [SessionExtension.None] in Semper. */
    @Volatile
    var session: SessionExtension = SessionExtension.None
        private set

    @Volatile
    private var installed = false

    /**
     * Installs a product's extensions: once, before `Application.onCreate`
     * returns. A second call throws, so two installers cannot both win.
     */
    @Synchronized
    fun install(session: SessionExtension = this.session) {
        check(!installed) { "AppExtensions are already installed" }
        this.session = session
        installed = true
    }

    /** Back to Semper's defaults, so one test's extension does not leak into the next. */
    @VisibleForTesting
    @Synchronized
    fun resetForTest() {
        session = SessionExtension.None
        installed = false
    }
}
