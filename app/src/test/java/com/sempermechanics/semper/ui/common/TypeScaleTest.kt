package com.sempermechanics.semper.ui.common

import android.app.Application
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The app's type scale (readiness AE1-011): Title 20, Subtitle 16, Body 14,
 * Label 13 and Caption 12 sp, as text_* dimens and TextAppearance.Semper.*
 * styles, and no layout text smaller than the caption.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TypeScaleTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun sp(styleRes: Int): Float {
        val ta = app.obtainStyledAttributes(styleRes, intArrayOf(android.R.attr.textSize))
        val px = ta.getDimension(0, 0f)
        ta.recycle()
        return px / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, app.resources.displayMetrics)
    }

    @Test
    fun `the scale's text appearances have their sizes`() {
        assertEquals(
            listOf(20f, 16f, 14f, 13f, 12f),
            listOf(
                R.style.TextAppearance_Semper_Title,
                R.style.TextAppearance_Semper_Subtitle,
                R.style.TextAppearance_Semper_Body,
                R.style.TextAppearance_Semper_Label,
                R.style.TextAppearance_Semper_Caption,
            ).map(::sp),
        )
    }

    @Test
    fun `no layout sets text below the caption size`() {
        val small = Regex("""android:textSize="(\d+(?:\.\d+)?)sp"""")
        val layouts = File("src/main/res/layout").listFiles().orEmpty().filter { it.extension == "xml" }
        val offenders = layouts.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val size = small.find(line)?.groupValues?.get(1)?.toFloat() ?: return@mapIndexedNotNull null
                if (size < CAPTION_SP) "${f.name}:${i + 1} ${size}sp" else null
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        const val CAPTION_SP = 12f
    }
}
