package com.campuschat.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain model representing an encrypted Signal text message envelope.
 * Strictly contains ONLY routing metadata and encrypted ciphertext bytes.
 * Never stores plaintext messages.
 */
@Serializable
data class EncryptedMessageEnvelope(
    val senderUserId: String,
    val senderDeviceId: String,
    val senderRegistrationId: Int,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val recipientRegistrationId: Int,
    val messageType: Int,
    val ciphertextBase64: String
)
