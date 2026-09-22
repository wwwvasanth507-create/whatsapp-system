package com.campuschat.app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO specifically used for inserting/upserting device registrations from Android client.
 * All properties do NOT specify default values so kotlinx.serialization is forced to
 * explicitly serialize every key into JSON (preventing platform or other required fields
 * from being omitted by encodeDefaults=false).
 */
@Serializable
data class DeviceInsertDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("platform") val platform: String,
    @SerialName("registration_id") val registrationId: Int
)

/**
 * DTO representing full device record fetched from Supabase.
 */
@Serializable
data class DeviceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("platform") val platform: String,
    @SerialName("registration_id") val registrationId: Int? = null,
    @SerialName("fcm_token") val fcmToken: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
