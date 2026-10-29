package com.sempermechanics.semper.ui.home

import android.app.Application
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.awaitCancellation
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
import org.robolectric.android.controller.ActivityController
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

    // ── A configuration change (readiness AE1-111) ─────────────────────────

    /** A screen with its own picker, built from [state] when it replaces one; [decode] is its photo read. */
    private fun screen(
        state: Bundle? = null,
        decode: suspend (Uri) -> Pair<String, DecodedReference>? = { "ref.jpg" to fits() },
    ): Pair<ActivityController<AppCompatActivity>, RerunReferencePicker> {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        val picker = RerunReferencePicker(
            built.get(),
            onPicked = { r, p -> started += r to p },
            decode = decode,
            io = Dispatchers.Main.immediate,
        )
        if (state == null) built.setup() else built.setup(state)
        idle()
        return built to picker
    }

    private fun fits() = DecodedReference(bytes, ImageSize(64, 48), null)

    /** What [built] saved, as a rotation saves it, then the screen destroyed. */
    private fun recreate(built: ActivityController<AppCompatActivity>): Bundle {
        val state = Bundle().also { built.saveInstanceState(it) }
        built.pause().stop().destroy()
        idle()
        return state
    }

    private fun alertsOn(screen: AppCompatActivity) = ShadowDialog.getShownDialogs()
        .filterIsInstance<AlertDialog>()
        .filter { it.isShowing && it.context.findActivity() === screen }

    private fun sheetsOn(screen: AppCompatActivity) = ShadowDialog.getShownDialogs()
        .filter { it.isShowing && it.context.findActivity() === screen }
        .filter { it.findViewById<TextView>(R.id.tvMediaTitle)?.text == screen.getString(R.string.reference_image) }

    private fun android.content.Context.findActivity(): AppCompatActivity? {
        var c: android.content.Context? = this
        while (c is android.content.ContextWrapper) {
            if (c is AppCompatActivity) return c
            c = c.baseContext
        }
        return null
    }

    @Test
    fun `Pick its reference photo is asked again on the rebuilt screen, for the same analysis`() {
        val record = record()
        val (first, picker) = screen()
        picker.ask(record)
        idle()

        val (again, _) = screen(recreate(first))

        val ask = alertsOn(again.get()).single()
        assertEquals(activity.getString(R.string.rerun_pick_reference_title), ask.title())
        assertEquals(activity.getString(R.string.rerun_pick_reference_body), ask.body())
        assertTrue("the old screen's question is closed", alertsOn(first.get()).isEmpty())
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("Choose a photo opens the reference sheet", 1, sheetsOn(again.get()).size)
        again.close()
    }

    @Test
    fun `the reference sheet is open again, and a photo picked from it re-runs the same analysis`() {
        val record = record()
        val (first, picker) = screen()
        picker.ask(record)
        idle()
        alertsOn(first.get()).single().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, sheetsOn(first.get()).size)

        val (again, rebuilt) = screen(recreate(first))

        assertEquals("one sheet, not two", 1, sheetsOn(again.get()).size)
        assertTrue("the old screen's sheet is closed", sheetsOn(first.get()).isEmpty())
        rebuilt.check(uri)
        idle()
        assertEquals(record.id, started.single().first.id)
        again.close()
    }

    @Test
    fun `a photo still being checked is checked again on the rebuilt screen`() {
        val record = record()
        val (first, picker) = screen(decode = { awaitCancellation() })
        picker.ask(record)
        idle()
        alertsOn(first.get()).forEach { it.dismiss() }
        picker.check(uri)
        idle()
        val waitTitle = activity.getString(R.string.rerun_checking_photo)
        assertTrue("the wait is up", alertsOn(first.get()).any { it.title() == waitTitle })

        val (again, _) = screen(recreate(first))

        assertEquals(record.id, started.single().first.id)
        assertTrue("the old screen's wait is closed", alertsOn(first.get()).isEmpty())
        again.close()
    }

    @Test
    fun `a photo of the wrong size is answered again on the rebuilt screen, with both sizes`() {
        val record = record()
        val (first, picker) = screen(decode = { "wide.jpg" to DecodedReference(bytes, ImageSize(48, 64), null) })
        picker.ask(record)
        idle()
        alertsOn(first.get()).forEach { it.dismiss() }
        picker.check(uri)
        idle()

        val (again, _) = screen(recreate(first))

        val retry = alertsOn(again.get()).single()
        assertEquals("This photo is 48 × 64; the analysis used 64 × 48.", retry.body())
        assertEquals(
            activity.getString(R.string.rerun_choose_another_photo),
            retry.getButton(AlertDialog.BUTTON_POSITIVE).text.toString(),
        )
        retry.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("Choose another photo opens the reference sheet", 1, sheetsOn(again.get()).size)
        again.close()
    }

    @Test
    fun `Can't re-run is said again on the rebuilt screen, with its reason`() {
        val (first, picker) = screen()
        // An analysis deleted while its reference was being picked.
        picker.ask(sessionRecord(id = "deleted", imgW = 64, imgH = 48))
        idle()
        alertsOn(first.get()).forEach { it.dismiss() }
        picker.check(uri)
        idle()

        val (again, _) = screen(recreate(first))

        val notice = alertsOn(again.get()).single()
        assertEquals(activity.getString(R.string.rerun_unavailable_title), notice.title())
        assertEquals(activity.getString(R.string.rerun_photos_missing_body), notice.body())
        again.close()
    }
}
