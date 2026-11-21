package com.lia.app.data.auth

import com.lia.app.BuildConfig

/**
 * Central access to public client credentials loaded from app/local.properties.
 * Ready for Phase 3 (Clerk + Supabase integration).
 */
object AuthConfig {
    val clerkPublishableKey: String
        get() = BuildConfig.CLERK_PUBLISHABLE_KEY

    val supabaseUrl: String
        get() = BuildConfig.SUPABASE_URL

    val supabaseAnonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY

    val isClerkConfigured: Boolean
        get() = clerkPublishableKey.isNotBlank() &&
            !clerkPublishableKey.contains("REEMPLAZA")

    val isSupabaseConfigured: Boolean
        get() = supabaseUrl.startsWith("https://") &&
            !supabaseUrl.contains("REEMPLAZA") &&
            supabaseAnonKey.isNotBlank() &&
            !supabaseAnonKey.contains("REEMPLAZA")
}
