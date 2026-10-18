package com.sempermechanics.semper.ui.limit

import android.app.Activity
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.databinding.ActivityUpdateRequiredBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.ExternalLinks

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
            private set

        /** This app's store listing, or its web page when no store app is installed. */
        fun openStore(host: Activity) {
            val id = host.packageName
            if (!ExternalLinks.openQuietly(host, "market://details?id=$id")) {
                ExternalLinks.open(host, "https://play.google.com/store/apps/details?id=$id")
            }
        }
    }
}
