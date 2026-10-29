package com.sempermechanics.semper.data.prefs

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The wizard builds its draft in onCreate, on the main thread; resolving
 * filesDir there is a disk check StrictMode reported on every open (readiness
 * TE1-103). The draft now resolves its folder at its first read or write,
 * which run on the draft's IO lane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardDraftLazyDirTest {

    @Test
    fun `a draft built from a context touches filesDir only when first used`() {
        var asked = 0
        val counting = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File {
                asked++
                return super.getFilesDir()
            }
        }

        val draft = WizardDraft(counting)
        assertEquals("filesDir read when the draft was built", 0, asked)

        draft.readFrames()
        draft.readMask()
        assertEquals(1, asked)
    }
}
