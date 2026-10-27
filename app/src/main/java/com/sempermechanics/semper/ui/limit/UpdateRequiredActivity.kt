package com.sempermechanics.semper.ui.limit

import android.app.Activity
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.databinding.ActivityUpdateRequiredBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.ExternalLinks
import timber.log.Timber

/**
 * Gate shown when the backend no longer supports this build
 * (`minSupportedVersionCode`, A6.1): on entry to Home, once per process, and
 * whenever new work is started.
 *
 * Like [SeatRequiredActivity], the user is **not blocked from the app**.
 * Every saved analysis stays open, viewable and exportable; only starting a
 * new analysis and moving backups to or from the cloud wait for the update
 * ([com.sempermechanics.semper.data.policy.FeatureGates]).
 */
@MainThread
class UpdateRequiredActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateRequiredBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateRequiredBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.updateRoot)
        shownThisProcess = true

        binding.btnUpdateApp.setOnClickListener { openStore(this) }
        binding.tvUpdateBack.setOnClickListener { finish() }
    }

    companion object {
        /** Whether Home has raised the gate since this process started; it raises it once. */
        @Volatile
        var shownThisProcess = false
            @VisibleForTesting internal set

        /**
         * Where this build updates from ([UpdateSource.urls]): installed by
         * Play, its store listing (or the listing's web page when no store
         * app handles it); otherwise the release download page.
         */
        fun openStore(host: Activity) {
            val urls = UpdateSource.urls(host.packageName, installingPackage(host))
            if (urls.dropLast(1).none { ExternalLinks.openQuietly(host, it) }) ExternalLinks.open(host, urls.last())
        }

        /** Who installed this build; null when the platform does not say. */
        private fun installingPackage(host: Activity): String? =
            runCatching { host.packageManager.getInstallSourceInfo(host.packageName).installingPackageName }
                .onFailure { Timber.d(it, "No install source for %s", host.packageName) }
                .getOrNull()
    }
}
