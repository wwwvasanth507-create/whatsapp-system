package com.campuschat.app

import android.app.Application
import android.util.Log
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.network.AndroidNetworkConnectivityObserver
import com.campuschat.app.core.network.NetworkStatus
import com.campuschat.app.core.network.SupabaseClientProvider
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.presentation.navigation.AppViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CampusChatApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        try {
            // 1. Initialize DeviceIdProvider for installation/device UUID tracking
            DeviceIdProvider.init(this)

            // 2. Initialize Supabase Kotlin SDK client
            SupabaseClientProvider.init()

            // 3. Initialize SessionManager for persistent session tracking
            SessionManager.init(SupabaseClientProvider.client)

            // 4. Initialize AppViewModelFactory with application context
            AppViewModelFactory.init(this)

            // 5. Start observing Supabase Realtime notifications safely
            try {
                AppViewModelFactory.realtimeMessageObserver?.startObserving()
            } catch (e: Exception) {
                Log.e("CampusChatApp", "Failed to start RealtimeMessageObserver on app startup", e)
            }

            // 6. Monitor network connectivity changes for auto-reconnect & outbox queue retry
            try {
                val networkObserver = AndroidNetworkConnectivityObserver(this)
                applicationScope.launch {
                    networkObserver.observeNetwork().collectLatest { status ->
                        if (status == NetworkStatus.Available) {
                            val userId = SessionManager.getCurrentUserId()
                            if (!userId.isNullOrEmpty()) {
                                val localDeviceId = DeviceIdProvider.getDeviceId()

                                // Re-validate/restart Realtime observer channel
                                AppViewModelFactory.realtimeMessageObserver?.reconnect()

                                // Fetch pending incoming messages
                                AppViewModelFactory.pendingMessageService.fetchAndDecryptPendingMessages(localDeviceId)

                                // Process pending outgoing outbox messages
                                AppViewModelFactory.outboxService.processPendingOutboxMessages(userId, localDeviceId)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CampusChatApp", "Failed to initialize NetworkConnectivityObserver", e)
            }

        } catch (e: Exception) {
            Log.e("CampusChatApp", "Error during CampusChatApp initialization", e)
        }
    }
}
