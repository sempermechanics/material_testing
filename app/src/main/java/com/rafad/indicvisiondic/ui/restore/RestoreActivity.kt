package com.rafad.indicvisiondic.ui.restore

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rafad.indicvisiondic.ui.home.HomeActivity

/**
 * Legacy entry point kept for compatibility. Restore is now surfaced under
 * Home Settings, so this simply routes back Home.
 */
class RestoreActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
        )
        finish()
    }
}
