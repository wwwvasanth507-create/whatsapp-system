package com.campuschat.app.domain.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.PendingTransportMessage

/**
 * Domain interface for carrying encrypted Signal ciphertext envelopes over Supabase transport.
 * Strictly operates on encrypted ciphertext envelopes. Never accepts or exposes plaintext.
 */
interface EncryptedMessageRepository {
    suspend fun sendEncryptedMessage(
        envelope: EncryptedMessageEnvelope,
        ttlSeconds: Int = 2592000
    ): Resource<String>

    suspend fun fetchPendingMessages(
        deviceId: String
    ): Resource<List<PendingTransportMessage>>

    suspend fun markMessageDelivered(
        messageId: String
    ): Resource<Boolean>

    suspend fun markMessageRead(
        messageId: String
    ): Resource<Boolean>
}
