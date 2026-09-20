package com.campuschat.app.core.network

import com.campuschat.app.core.config.AppConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

object SupabaseClientProvider {

    lateinit var client: SupabaseClient
        private set

    fun init() {
        client = createSupabaseClient(
            supabaseUrl = AppConfig.SUPABASE_URL,
            supabaseKey = AppConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }
}
