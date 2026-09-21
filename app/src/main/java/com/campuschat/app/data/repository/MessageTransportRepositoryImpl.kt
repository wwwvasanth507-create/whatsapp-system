package com.campuschat.app.data.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.dto.AcknowledgeMessageParams
import com.campuschat.app.data.dto.EncryptedMessageDto
import com.campuschat.app.data.dto.EnqueueMessageParams
import com.campuschat.app.data.dto.FetchPendingParams
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.PendingTransportMessage
import com.campuschat.app.domain.repository.MessageTransportRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.Json

class MessageTransportRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : MessageTransportRepository {

    companion object {
        const val MAX_CIPHERTEXT_LENGTH = 30000
    }

    override suspend fun enqueueEncryptedMessage(
        envelope: EncryptedMessageEnvelope,
        ttlSeconds: Int
    ): Resource<String> {
        return try {
            val serializedEnvelope = json.encodeToString(EncryptedMessageEnvelope.serializer(), envelope)
            if (serializedEnvelope.length > MAX_CIPHERTEXT_LENGTH) {
                return Resource.Error("Encrypted payload exceeds maximum permitted transport size")
            }

            val params = EnqueueMessageParams(
                senderDeviceId = envelope.senderDeviceId,
                recipientUserId = envelope.recipientUserId,
                recipientDeviceId = envelope.recipientDeviceId,
                messageType = envelope.messageType,
                ciphertext = serializedEnvelope,
                ttlSeconds = ttlSeconds
            )

            val messageId = supabaseClient.postgrest.rpc(
                function = "enqueue_encrypted_message",
                parameters = params
            ).decodeAs<String>()

            Resource.Success(messageId)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun fetchPendingMessages(
        deviceId: String
    ): Resource<List<PendingTransportMessage>> {
        return try {
            val params = FetchPendingParams(recipientDeviceId = deviceId)
            val dtos = supabaseClient.postgrest.rpc(
                function = "fetch_pending_messages",
                parameters = params
            ).decodeList<EncryptedMessageDto>()

            val pendingList = dtos.mapNotNull { dto ->
                try {
                    val envelope = json.decodeFromString(EncryptedMessageEnvelope.serializer(), dto.ciphertext)
                    PendingTransportMessage(
                        messageId = dto.id ?: return@mapNotNull null,
                        envelope = envelope,
                        serverCreatedAt = dto.serverCreatedAt
                    )
                } catch (e: Exception) {
                    null // Skip corrupted envelopes safely without throwing
                }
            }

            Resource.Success(pendingList)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun acknowledgeMessage(
        messageId: String
    ): Resource<Boolean> {
        return try {
            val params = AcknowledgeMessageParams(messageId = messageId)
            val success = supabaseClient.postgrest.rpc(
                function = "acknowledge_message_delivery",
                parameters = params
            ).decodeAs<Boolean>()

            Resource.Success(success)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun deleteDeliveredMessage(
        messageId: String
    ): Resource<Boolean> {
        return acknowledgeMessage(messageId)
    }

    private fun sanitizeErrorMessage(e: Exception): String {
        val msg = e.message ?: "Unknown error"
        return msg.replace(Regex("(?i)secret|key|private|password|token"), "[REDACTED]")
    }
}
