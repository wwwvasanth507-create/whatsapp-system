package com.campuschat.app.domain.model

/**
 * Domain model representing a recipient device's public key bundle fetched from the server.
 * Strictly contains ONLY public key material required for X3DH session establishment.
 */
data class RemotePreKeyBundle(
    val deviceId: String,
    val userId: String,
    val registrationId: Int,
    val identityPublicKeyBase64: String,
    val signedPreKeyId: Int,
    val signedPreKeyBase64: String,
    val signedPreKeySignatureBase64: String,
    val oneTimePreKeyId: Int?,
    val oneTimePreKeyBase64: String?
)
