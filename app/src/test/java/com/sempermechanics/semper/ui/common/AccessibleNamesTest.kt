package com.sempermechanics.semper.ui.common

import android.app.Application
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.media.MediaGridAdapter
import com.sempermechanics.semper.ui.common.media.MediaStoreBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Controls TalkBack has to name (readiness AE1V-007): the consent switches, the
 * parameter sliders and each gallery tile, which also says whether it is picked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccessibleNamesTest {

    private val themed = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )

    private fun inflateMerge(layout: Int): View = LinearLayout(themed).also {
        LayoutInflater.from(themed).inflate(layout, it, true)
    }

    private fun assertNamed(root: View, vararg ids: Int) {
        for (id in ids) {
            val name = root.findViewById<View>(id).contentDescription
            assertTrue("${themed.resources.getResourceEntryName(id)} has no name", !name.isNullOrBlank())
        }
    }

    @Test
    fun `the consent switches are named`() {
        assertNamed(
            inflateMerge(R.layout.view_settings_scroll_content),
            R.id.switchDiagnostics,
            R.id.switchImprovementConsent,
        )
    }

    @Test
    fun `the parameter sliders are named`() {
        assertNamed(
            inflateMerge(R.layout.wizard_step_settings_content),
            R.id.sliderSubsetSize,
            R.id.sliderStepSize,
            R.id.sliderStrainWindow,
        )
    }

    @Test
    fun `a gallery tile is named for its file and says when it is picked`() {
        fun uri(id: Long) = Uri.parse("content://media/external/images/media/$id")
        val picked = uri(1)
        val items = listOf(
            MediaStoreBrowser.Item(1, picked, "specimen_01.png", "image/png", isVideo = false),
            MediaStoreBrowser.Item(2, uri(2), "specimen_02.png", "image/png", isVideo = false),
        )
        val adapter = MediaGridAdapter(themed.contentResolver, isSelected = { it == picked }, onClick = {})
        try {
            adapter.submit(items)
            val parent = RecyclerView(themed).apply { layoutManager = GridLayoutManager(themed, 3) }
            val tiles = items.indices.map { position ->
                adapter.createViewHolder(parent, 0).also { adapter.bindViewHolder(it, position) }.itemView
            }
            assertEquals("specimen_01.png", tiles[0].contentDescription)
            assertEquals("specimen_02.png", tiles[1].contentDescription)
            assertTrue(tiles[0].isSelected)
            assertFalse(tiles[1].isSelected)
        } finally {
            adapter.shutdown()
        }
    }
}
