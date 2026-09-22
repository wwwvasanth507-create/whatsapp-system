package com.campuschat.app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceIdentityKeyDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("identity_public_key") val identityPublicKey: String
)

@Serializable
data class DeviceSignedPreKeyDto(
    @SerialName("id") val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    @SerialName("signature") val signature: String,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class DeviceKyberPreKeyDto(
    @SerialName("id") val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    @SerialName("signature") val signature: String,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class DeviceOneTimePreKeyDto(
    @SerialName("id") val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    @SerialName("is_consumed") val isConsumed: Boolean = false
)

@Serializable
data class ClaimedPreKeyBundleDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("registration_id") val registrationId: Int = 0,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_prekey_id") val signedPreKeyId: Int,
    @SerialName("signed_prekey") val signedPreKey: String,
    @SerialName("signed_prekey_signature") val signedPreKeySignature: String,
    @SerialName("one_time_prekey_id") val oneTimePreKeyId: Int? = null,
    @SerialName("one_time_prekey") val oneTimePreKey: String? = null,
    @SerialName("kyber_prekey_id") val kyberPreKeyId: Int? = null,
    @SerialName("kyber_prekey") val kyberPreKey: String? = null,
    @SerialName("kyber_prekey_signature") val kyberPreKeySignature: String? = null
)
