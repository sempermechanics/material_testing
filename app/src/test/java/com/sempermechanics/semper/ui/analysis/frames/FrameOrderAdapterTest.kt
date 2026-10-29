package com.sempermechanics.semper.ui.analysis.frames

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.media.ThumbnailLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executor

/**
 * The deformed-frame strip on wizard step 1: dropping a dragged tile renumbers
 * every badge and returns the tile to its resting size. The badge refresh is a
 * payload change, and the item animator ends a holder's running animation on a
 * change, which once froze the drop's shrink at the dragged 1.06×.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FrameOrderAdapterTest {

    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )
    private val orders = mutableListOf<List<String>>()
    private val adapter = FrameOrderAdapter { orders += it }
    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter = this@FrameOrderAdapterTest.adapter
    }

    @Before
    fun setUp() {
        adapter.submit(listOf("a.png", "b.png", "c.png")) // missing files draw the placeholder
        layout()
    }

    private fun layout() {
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
        )
        recycler.layout(0, 0, W, H)
    }

    private fun tile(position: Int): View = recycler.findViewHolderForAdapterPosition(position)!!.itemView

    private fun badge(position: Int): String =
        tile(position).findViewById<android.widget.TextView>(R.id.tvFrameBadge).text.toString()

    @Test
    fun `a dropped tile shrinks back although the badges refresh at once`() {
        val dragged = tile(0)
        FrameOrderAdapter.applyDragging(dragged, dragging = true, animate = false)
        adapter.moveItem(0, 2)
        layout()

        // What ItemTouchHelper's clearView does on the drop.
        FrameOrderAdapter.applyDragging(dragged, dragging = false)
        adapter.refreshBadges()
        layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(1f, dragged.scaleX)
        assertEquals(1f, dragged.scaleY)
        assertEquals(0f, dragged.elevation)
    }

    @Test
    fun `a move renumbers the badges in the new order`() {
        adapter.moveItem(0, 2)
        adapter.refreshBadges()
        layout()

        assertEquals(listOf("b.png", "c.png", "a.png"), adapter.currentPaths())
        assertEquals(listOf("1", "2", "3"), (0..2).map(::badge))
    }

    private fun thumbKey(position: Int): Any? =
        tile(position).findViewById<android.widget.ImageView>(R.id.imgFrameThumb).tag

    /**
     * Each tile's thumbnail key is its file's path, length and time, read with
     * a stat. It was read in onBindViewHolder, on the main thread, six times a
     * strip on the API 30 StrictMode run (readiness TE1-103). Now a submitted
     * list is stat'ed on the adapter's stat executor and the tiles bind the
     * keys when they land, so binding never touches the disk.
     */
    @Test
    fun `frame keys are read off the main thread and bound when they land`() {
        val statThreads = mutableListOf<Thread>()
        val pending = mutableListOf<Runnable>()
        val sizes = mutableMapOf("x.png" to 1L, "y.png" to 2L)
        val keyed = FrameOrderAdapter(
            thumbnails = ThumbnailLoader(
                maxCached = 4,
                executor = Executor { },
                decode = { ThumbnailLoader.Decoded.Missing },
            ),
            statExecutor = Executor { pending += it },
            statKey = { path ->
                synchronized(statThreads) { statThreads += Thread.currentThread() }
                FrameOrderAdapter.FrameThumb(path, sizes.getValue(path), 0L)
            },
        ) {}
        recycler.adapter = keyed
        fun statOnWorker(): Thread {
            val worker = Thread { pending.toList().also { pending.clear() }.forEach { it.run() } }
            worker.start()
            worker.join()
            shadowOf(Looper.getMainLooper()).idle()
            layout()
            return worker
        }

        keyed.submit(listOf("x.png", "y.png"))
        layout()
        assertTrue("stat'ed while binding: $statThreads", statThreads.isEmpty())
        assertNull("no key before the stat lands", thumbKey(0))

        val worker = statOnWorker()
        assertEquals(setOf(worker), statThreads.toSet())
        assertEquals(FrameOrderAdapter.FrameThumb("x.png", 1L, 0L), thumbKey(0))
        assertEquals(FrameOrderAdapter.FrameThumb("y.png", 2L, 0L), thumbKey(1))

        // A sort renames the staged files: the same path can come back holding another frame.
        statThreads.clear()
        sizes["x.png"] = 7L
        keyed.submit(listOf("x.png", "y.png"))
        layout()
        assertTrue("stat'ed while binding: $statThreads", statThreads.isEmpty())
        statOnWorker()
        assertEquals(FrameOrderAdapter.FrameThumb("x.png", 7L, 0L), thumbKey(0))
    }

    private companion object {
        const val W = 1080
        const val H = 240
    }
}
