package com.rafad.indicvisiondic

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

object SupabaseManager {

    // Your URL
    private const val SUPABASE_URL = "https://usvcxiqtslxoaygrphhh.supabase.co"

    // Your Publishable Key
    private const val SUPABASE_ANON_KEY = "sb_publishable_8ZC_4X8bV-okWtlJ0t9I7g_jFdpNCJe"

    // The single, globally accessible client instance
    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_ANON_KEY
    ) {
        // We tell the client to activate the 3 specific modules we installed
        install(Auth)
        install(Postgrest)
        install(Storage)
    }
}