package com.sempermechanics.semper.data.policy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.data.AppForeground
import timber.log.Timber

/**
 * Reads the EMM's managed configuration ([ManagedConfig], schema in
 * `res/xml/app_restrictions.xml`) through [RestrictionsManager], and keeps it
 * current. Per the platform's guidance, the change broadcast
 * ([Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED]) is received only while
 * the app is in the foreground, from a receiver registered at runtime; each
 * return to the foreground reads the restrictions again, which covers a
 * change made while the app was in the background.
 */
object ManagedConfigReader {

    @Volatile
    private var cached: ManagedConfig? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh(context)
        }
    }

    /** The managed configuration, read once and kept until it changes. */
    fun current(context: Context): ManagedConfig = cached ?: refresh(context)

    /** Read the restrictions again. Never throws: an unreadable policy is no policy. */
    fun refresh(context: Context): ManagedConfig {
        val config = runCatching { read(context.applicationContext) }
            .onFailure { Timber.w(it, "Could not read the managed configuration") }
            .getOrDefault(ManagedConfig.UNMANAGED)
        if (config != cached) Timber.d("Managed configuration read (managed: %b)", config != ManagedConfig.UNMANAGED)
        cached = config
        return config
    }

    /**
     * For [AppForeground.install], from [Application.onCreate]: listen for
     * changes while any screen of the app is started, and read the
     * restrictions again each time the first one starts.
     */
    fun foregroundListener(app: Application): AppForeground.Listener = ForegroundListener(app)

    private fun read(context: Context): ManagedConfig {
        val bundle = context.getSystemService(RestrictionsManager::class.java)?.applicationRestrictions
            ?: return ManagedConfig.UNMANAGED
        return ManagedConfig.from(bundle.keySet().associateWith { bundle.value(it) })
    }

    @Suppress("DEPRECATION") // Bundle.get(String): the typed getters need the type up front
    private fun Bundle.value(key: String): Any? = get(key)

    /** Registers [receiver] when the first screen starts, and drops it when the last one stops. */
    private class ForegroundListener(private val app: Application) : AppForeground.Listener {
        override fun onForeground() {
            ContextCompat.registerReceiver(
                app,
                ManagedConfigReader.receiver,
                IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            ManagedConfigReader.refresh(app)
        }

        override fun onBackground() {
            runCatching { app.unregisterReceiver(ManagedConfigReader.receiver) }
        }
    }
}
