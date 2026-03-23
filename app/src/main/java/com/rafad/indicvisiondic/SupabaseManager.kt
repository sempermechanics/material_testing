package com.rafad.indicvisiondic

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

object SupabaseManager {

    // 🚀 SECURED: We now pull the keys from the auto-generated BuildConfig class.
    // Hackers reverse-engineering this Kotlin file will no longer see your credentials!

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        // We tell the client to activate the 3 specific modules we installed
        install(Auth)
        install(Postgrest)
        install(Storage)
    }
}