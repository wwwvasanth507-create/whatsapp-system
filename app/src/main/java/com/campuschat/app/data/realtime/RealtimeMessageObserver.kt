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

    fun startObserving(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        stopObserving()
        job = scope.launch {
            try {
                val channel = supabaseClient.realtime.channel("public_messages_notification")
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "messages"
                }
                
                channel.subscribe()

                changeFlow
                    .catch { /* ignore network error, fall back to periodic poll if offline */ }
                    .collect { action ->
                        val localDeviceId = DeviceIdProvider.getDeviceId()
                        // Realtime event acts purely as a notification trigger to fetch pending messages via secure RPC
                        pendingMessageService.fetchAndDecryptPendingMessages(localDeviceId)
                    }
            } catch (e: Exception) {
                // Safe error handling without logging message content
            }
        }
    }

    fun stopObserving() {
        job?.cancel()
        job = null
    }
}
