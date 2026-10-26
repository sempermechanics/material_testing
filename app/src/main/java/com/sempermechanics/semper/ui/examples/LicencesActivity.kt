package com.sempermechanics.semper.ui.examples

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.inSpans
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.examples.ExampleCatalog
import com.sempermechanics.semper.databinding.ActivityLicencesBinding
import com.sempermechanics.semper.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * Credits & licences (ADR-031): who made each bundled example set and under
 * which licence, then the full texts of the licences the app redistributes
 * (`assets/licences/`): DICe's BSD-3-Clause, whose clause 2 asks for it, and
 * the OpenCV and Eigen licences of the engine's libraries.
 */
@MainThread
class LicencesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicencesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicencesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.licencesRoot)
        binding.btnLicencesBack.setOnClickListener { finish() }
        lifecycleScope.launch {
            binding.tvLicences.text = withContext(Dispatchers.IO) { build(this@LicencesActivity, assets) }
        }
    }

    companion object {
        /** Where the full licence texts live in the APK's assets. */
        const val LICENCES_DIR = "licences"

        fun intent(context: Context): Intent = Intent(context, LicencesActivity::class.java)

        /** The page's text: one credit per example set, then each licence in full. */
        fun build(context: Context, assets: AssetManager): CharSequence {
            val text = SpannableStringBuilder()
            text.inSpans(StyleSpan(Typeface.BOLD)) { append(context.getString(R.string.licences_examples)) }
            text.append("\n\n")
            ExampleCatalog.load(assets).forEach { set ->
                text.inSpans(StyleSpan(Typeface.BOLD)) { append(set.title) }
                text.append("\n").append(ExampleDetailSheet.creditText(context, set)).append("\n\n")
            }
            val files = try {
                assets.list(LICENCES_DIR).orEmpty().sorted()
            } catch (e: IOException) {
                Timber.w(e, "No licence texts")
                emptyList()
            }
            files.forEach { name ->
                text.inSpans(StyleSpan(Typeface.BOLD)) { append(name.removeSuffix(".txt")) }
                text.append("\n")
                text.append(assets.open("$LICENCES_DIR/$name").use { it.readBytes().decodeToString() }.trim())
                text.append("\n\n")
            }
            return text
        }
    }
}
