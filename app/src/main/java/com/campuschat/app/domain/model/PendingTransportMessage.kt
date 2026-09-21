package com.campuschat.app.domain.model

/**
 * Encapsulates a fetched pending ciphertext envelope together with its server message ID.
 */
data class PendingTransportMessage(
    val messageId: String,
    val envelope: EncryptedMessageEnvelope,
    val serverCreatedAt: String? = null
)
