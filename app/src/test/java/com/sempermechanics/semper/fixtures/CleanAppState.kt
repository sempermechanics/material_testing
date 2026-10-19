package com.sempermechanics.semper.fixtures

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.session.SessionStore
import org.junit.rules.ExternalResource
import java.lang.reflect.Modifier

/**
 * Signed out with no saved sessions, before and after each test: clears the
 * token store (and with it the remote config) and empties the session index
 * and its directories. Runs around the test's own @Before/@After.
 *
 * It also forgets the [Application][android.app.Application] that
 * [ViewModelProvider.AndroidViewModelFactory] keeps for the process
 * ([forgetViewModelFactoryApplication]), so a screen's view model is handed
 * this test's application rather than the first test's.
 */
class CleanAppState : ExternalResource() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    override fun before() {
        forgetViewModelFactoryApplication()
        AccountCache.clear(context)
        SessionStore.deleteAll(context)
    }

    override fun after() {
        SessionStore.deleteAll(context)
        AccountCache.clear(context)
    }

    private companion object {
        /**
         * An `AndroidViewModel` whose only constructor takes the
         * application (SessionLimitViewModel, SettingsViewModel, HomeViewModel,
         * ...) is built by the singleton `AndroidViewModelFactory.getInstance`,
         * which keeps the application it was first called with and ignores the
         * one in the creation extras. On a phone there is one application, so
         * that is right. Robolectric builds a new application for every test,
         * each with its own files and preferences, so without this a view
         * model's `getApplication()` was the first test's: a limit re-check
         * read another test's empty preferences, found no device stop and
         * reported room. Clearing the singleton makes the next `getInstance`
         * take this test's application. Found by the field's type, so a
         * renamed field is still cleared; a release without the singleton has
         * nothing to clear.
         */
        fun forgetViewModelFactoryApplication() {
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == factory }
                .forEach { field ->
                    field.isAccessible = true
                    field.set(null, null)
                }
        }
    }
}
