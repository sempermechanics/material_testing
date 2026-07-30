package com.indicvision.semper.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Transparent proxy launched from the system share chooser as the "Save to Files"
 * destination. Opens SAF, copies the staged file, then finishes.
 */
class SaveExportActivity : AppCompatActivity() {

    private var pendingFile: File? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        val file = pendingFile?.takeIf { it.exists() }
        if (result.resultCode != RESULT_OK || uri == null || file == null) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } != null
                }.onFailure { Timber.e(it, "Save to Files failed") }.getOrDefault(false)
            }
            Toast.makeText(
                this@SaveExportActivity,
                if (ok) R.string.save_success else R.string.save_failed,
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "*/*"
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pendingFile = file
        createDocument.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, file.name)
            },
        )
    }

    companion object {
        const val EXTRA_PATH = "save_export_path"
        const val EXTRA_MIME = "save_export_mime"

        fun intent(host: android.content.Context, file: File, mime: String): Intent =
            Intent(host, SaveExportActivity::class.java).apply {
                putExtra(EXTRA_PATH, file.absolutePath)
                putExtra(EXTRA_MIME, mime)
            }
    }
}
