package com.sempermechanics.semper.ui.home

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.frames.DecodedReference
import com.sempermechanics.semper.ui.analysis.rerun.PickedReference
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Home's Re-run of an analysis with no kept reference (owner decision,
 * 2026-10-15): "Pick its reference photo", then the picker; the picked photo
 * must be the analysis's size. One that is not says both sizes and starts
 * nothing; one that is goes to the cache and starts the Re-run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RerunReferencePickerTest {

    @get:Rule
    val clean = CleanAppState()

    private lateinit var activity: AppCompatActivity
    private lateinit var picker: RerunReferencePicker
    private val started = mutableListOf<Pair<SessionRecord, PickedReference>>()
    private var decoded: DecodedReference? = null
    private val bytes = ByteArray(16) { it.toByte() }
    private val uri = Uri.parse("content://media/ref")

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        picker = RerunReferencePicker(
            built.get(),
            onPicked = { record, picked -> started += record to picked },
            decode = { decoded?.let { "again.jpg" to it } },
            io = Dispatchers.Main.immediate,
        )
        activity = built.setup().get()
    }

    @After
    fun tearDown() {
        File(activity.cacheDir, CacheJanitor.RERUN_REFERENCE_CACHE).delete()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun latest() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.title() = findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString()

    private fun AlertDialog.body() = findViewById<TextView>(android.R.id.message)?.text.toString()

    private fun record(): SessionRecord = sessionRecord(id = "old", imgW = 64, imgH = 48).also {
        assertTrue(SessionStore.upsert(activity, it, true))
    }

    @Test
    fun `it asks for the reference before anything is picked`() {
        picker.ask(record())
        idle()

        assertEquals(activity.getString(R.string.rerun_pick_reference_title), latest().title())
        assertEquals(activity.getString(R.string.rerun_pick_reference_body), latest().body())
        assertEquals(
            activity.getString(R.string.rerun_choose_photo),
            latest().getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
        )
        assertTrue(started.isEmpty())
    }

    @Test
    fun `a photo of the analysis's size starts the Re-run with it`() {
        val record = record()
        picker.ask(record)
        decoded = DecodedReference(bytes, ImageSize(64, 48), null)

        picker.check(uri)
        idle()

        val (forRecord, picked) = started.single()
        assertEquals(record.id, forRecord.id)
        assertEquals("again.jpg", picked.name)
        assertEquals(File(activity.cacheDir, CacheJanitor.RERUN_REFERENCE_CACHE), picked.file)
        assertArrayEquals(bytes, picked.file.readBytes())
    }

    @Test
    fun `a photo of another size says both sizes and starts nothing`() {
        picker.ask(record())
        decoded = DecodedReference(bytes, ImageSize(48, 64), null)

        picker.check(uri)
        idle()

        assertTrue(started.isEmpty())
        assertEquals("This photo is 48 × 64; the analysis used 64 × 48.", latest().body())
        assertEquals(
            activity.getString(R.string.rerun_choose_another_photo),
            latest().getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
        )
        assertFalse(File(activity.cacheDir, CacheJanitor.RERUN_REFERENCE_CACHE).exists())
    }

    @Test
    fun `a photo that cannot be decoded starts nothing`() {
        picker.ask(record())
        decoded = null

        picker.check(uri)
        idle()

        assertTrue(started.isEmpty())
        assertEquals(activity.getString(R.string.failed_load_reference), latest().body())
    }

    @Test
    fun `the analysis being re-run outlives the screen being recreated`() {
        val record = record()
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        val first = RerunReferencePicker(built.get(), onPicked = { _, _ -> }, decode = { null })
        built.setup()
        first.ask(record)
        val state = android.os.Bundle().also { built.saveInstanceState(it) }
        built.pause().stop().destroy()

        val again = Robolectric.buildActivity(AppCompatActivity::class.java)
        again.get().setTheme(R.style.Theme_Semper)
        val second = RerunReferencePicker(
            again.get(),
            onPicked = { r, p -> started += r to p },
            decode = { "ref.jpg" to DecodedReference(bytes, ImageSize(64, 48), null) },
            io = Dispatchers.Main.immediate,
        )
        again.setup(state)

        second.check(uri)
        idle()

        assertEquals(record.id, started.single().first.id)
        again.close()
    }
}
