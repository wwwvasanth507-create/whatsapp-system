package com.campuschat.app.data.realtime

import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.domain.service.PendingMessageService
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class RealtimeMessageObserver(
    private val supabaseClient: SupabaseClient,
    private val pendingMessageService: PendingMessageService
) {
    private var job: Job? = null

    val isObserving: Boolean
        get() = job?.isActive == true

    @Synchronized
    fun startObserving(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        if (isObserving) {
            // Already observing - prevent duplicate channel subscriptions
            return
        }
        stopObserving()

        // Check if an authenticated session exists before connecting
        val userId = com.campuschat.app.core.session.SessionManager.getCurrentUserId()
        if (userId.isNullOrEmpty()) {
            return
        }

        job = scope.launch {
            try {
                val channel = supabaseClient.realtime.channel("public_messages_notification_${userId.take(8)}")
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "messages"
                }

                channel.subscribe()

                // Trigger an initial fetch when starting observation to collect any missed messages
                val initialDeviceId = DeviceIdProvider.getDeviceId()
                pendingMessageService.fetchAndDecryptPendingMessages(initialDeviceId)

                changeFlow
                    .catch { /* ignore network error; retry trigger handling on reconnect */ }
                    .collect { action ->
                        val localDeviceId = DeviceIdProvider.getDeviceId()
                        // Realtime payload is strictly treated as a trigger/notification ONLY.
                        // Zero plaintext or ciphertext is read from the realtime payload.
                        pendingMessageService.fetchAndDecryptPendingMessages(localDeviceId)
                    }
            } catch (e: Exception) {
                // Safe error handling without logging sensitive message or key data
            }
        }
    }

    @Synchronized
    fun stopObserving() {
        job?.cancel()
        job = null
    }

    @Synchronized
    fun reconnect(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        stopObserving()
        startObserving(scope)
    }
}
