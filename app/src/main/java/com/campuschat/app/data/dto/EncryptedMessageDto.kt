package com.campuschat.app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EncryptedMessageDto(
    @SerialName("id") val id: String? = null,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("sender_device_id") val senderDeviceId: String,
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("recipient_device_id") val recipientDeviceId: String,
    @SerialName("message_type") val messageType: Int,
    @SerialName("ciphertext") val ciphertext: String,
    @SerialName("server_created_at") val serverCreatedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

@Serializable
data class EnqueueMessageParams(
    @SerialName("p_sender_device_id") val senderDeviceId: String,
    @SerialName("p_recipient_user_id") val recipientUserId: String,
    @SerialName("p_recipient_device_id") val recipientDeviceId: String,
    @SerialName("p_message_type") val messageType: Int,
    @SerialName("p_ciphertext") val ciphertext: String,
    @SerialName("p_ttl_seconds") val ttlSeconds: Int = 2592000
)

@Serializable
data class FetchPendingParams(
    @SerialName("p_recipient_device_id") val recipientDeviceId: String
)

@Serializable
data class AcknowledgeMessageParams(
    @SerialName("p_message_id") val messageId: String
)
