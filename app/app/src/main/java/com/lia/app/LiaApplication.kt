package com.lia.app

import android.app.Application
import com.clerk.api.Clerk
import com.lia.app.data.auth.AuthConfig

class LiaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (AuthConfig.isClerkConfigured) {
            Clerk.initialize(
                context = this,
                publishableKey = AuthConfig.clerkPublishableKey
            )
        }
    }
}
