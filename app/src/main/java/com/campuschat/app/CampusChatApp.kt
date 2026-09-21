package com.campuschat.app

import android.app.Application
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.network.SupabaseClientProvider
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.presentation.navigation.AppViewModelFactory

class CampusChatApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize DeviceIdProvider for installation/device UUID tracking
        DeviceIdProvider.init(this)

        // 2. Initialize Supabase Kotlin SDK client
        SupabaseClientProvider.init()

        // 3. Initialize SessionManager for persistent session tracking
        SessionManager.init(SupabaseClientProvider.client)

        // 4. Initialize AppViewModelFactory with application context
        AppViewModelFactory.init(this)

        // 5. Start observing Supabase Realtime notifications
        AppViewModelFactory.realtimeMessageObserver?.startObserving()
    }
}
