package com.campuschat.app

import android.app.Application
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.network.SupabaseClientProvider
import com.campuschat.app.core.session.SessionManager

class CampusChatApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize DeviceIdProvider for installation/device UUID tracking
        DeviceIdProvider.init(this)

        // 2. Initialize Supabase Kotlin SDK client
        SupabaseClientProvider.init()

        // 3. Initialize SessionManager for persistent session tracking
        SessionManager.init(SupabaseClientProvider.client)
    }
}
