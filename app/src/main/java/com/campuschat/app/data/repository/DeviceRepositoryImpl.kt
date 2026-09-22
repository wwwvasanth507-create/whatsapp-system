package com.campuschat.app.data.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.dto.DeviceInsertDto
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.repository.DeviceRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant

class DeviceRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val registrationIdProvider: (() -> Int)? = null
) : DeviceRepository {

    override suspend fun registerOrUpdateDevice(device: UserDevice): Resource<UserDevice> {
        return try {
            val deviceId = device.id ?: return Resource.Error("Device ID cannot be null for registration.")
            val regId = device.registrationId ?: registrationIdProvider?.invoke() ?: 1
            val dto = DeviceInsertDto(
                id = deviceId,
                userId = device.userId,
                deviceName = device.deviceName,
                platform = device.platform,
                registrationId = regId
            )
            supabaseClient.postgrest["devices"].upsert(dto)
            Resource.Success(device.copy(registrationId = regId))
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun updateLastSeen(userId: String, deviceId: String): Resource<Unit> {
        return try {
            val currentIsoTimestamp = Instant.now().toString()
            supabaseClient.postgrest["devices"].update(
                mapOf("last_seen_at" to currentIsoTimestamp)
            ) {
                filter {
                    eq("user_id", userId)
                    eq("id", deviceId)
                }
            }
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    private fun sanitizeErrorMessage(e: Exception): String {
        val raw = e.message ?: return "Device registration error."
        return raw.substringBefore("\nURL:").substringBefore("\nHeaders:").trim()
    }
}
