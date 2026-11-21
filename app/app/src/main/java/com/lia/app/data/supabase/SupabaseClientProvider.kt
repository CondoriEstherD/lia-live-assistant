package com.lia.app.data.supabase

import com.lia.app.data.auth.AuthConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

/**
 * Provides the Supabase client configured with Clerk as Third-Party Auth provider.
 * Do NOT install the GoTrue Auth plugin: every PostgREST / Storage request attaches
 * the fresh Clerk session JWT via [accessToken].
 */
object SupabaseClientProvider {
    @Volatile
    private var instance: SupabaseClient? = null

    fun getOrCreate(tokenProvider: suspend () -> String?): SupabaseClient {
        return instance ?: synchronized(this) {
            instance ?: createSupabaseClient(
                supabaseUrl = AuthConfig.supabaseUrl,
                supabaseKey = AuthConfig.supabaseAnonKey
            ) {
                accessToken = { tokenProvider() }
                install(Postgrest)
                install(Storage)
            }.also { instance = it }
        }
    }
}
