package com.campuschat.app.domain.model

import org.signal.libsignal.protocol.SignalProtocolAddress

sealed class X3DHSessionResult {
    data class SessionEstablished(
        val address: SignalProtocolAddress,
        val remoteDeviceId: String,
        val claimedOneTimePreKeyId: Int?
    ) : X3DHSessionResult()

    data class IdentityChanged(
        val remoteUserId: String,
        val remoteDeviceId: String
    ) : X3DHSessionResult()

    data class InvalidPreKeyBundle(val reason: String) : X3DHSessionResult()
    data class RecipientDeviceUnavailable(val reason: String) : X3DHSessionResult()
    object AuthenticationRequired : X3DHSessionResult()
    data class CryptoFailure(val reason: String) : X3DHSessionResult()
    data class StorageFailure(val reason: String) : X3DHSessionResult()
    data class NetworkFailure(val reason: String) : X3DHSessionResult()
}
