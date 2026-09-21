package com.campuschat.app.data.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.dto.DeviceDto
import com.campuschat.app.data.dto.ProfileDto
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.UserDiscoveryRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class UserDiscoveryRepositoryImpl(
    private val supabaseClient: SupabaseClient
) : UserDiscoveryRepository {

    override suspend fun searchUsers(query: String): Resource<List<UserProfile>> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return Resource.Success(emptyList())
        }
        return try {
            val profiles = supabaseClient.postgrest["profiles"]
                .select {
                    filter {
                        or {
                            ilike("username", "%$trimmed%")
                            ilike("display_name", "%$trimmed%")
                        }
                    }
                }.decodeList<ProfileDto>()

            val resultList = profiles.map { dto ->
                UserProfile(
                    id = dto.id ?: "",
                    username = dto.username ?: "",
                    displayName = dto.displayName ?: dto.username ?: "Campus User",
                    avatarUrl = dto.avatarUrl,
                    createdAt = dto.createdAt,
                    updatedAt = dto.updatedAt
                )
            }
            Resource.Success(resultList)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun getRecipientDevices(userId: String): Resource<List<UserDevice>> {
        if (userId.isBlank()) {
            return Resource.Success(emptyList())
        }
        return try {
            val devices = supabaseClient.postgrest["devices"]
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeList<DeviceDto>()

            val deviceList = devices.mapNotNull { dto ->
                val deviceId = dto.id ?: return@mapNotNull null
                UserDevice(
                    id = deviceId,
                    userId = dto.userId,
                    deviceName = dto.deviceName,
                    platform = dto.platform
                )
            }
            Resource.Success(deviceList)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    private fun sanitizeErrorMessage(e: Exception): String {
        val raw = e.message ?: return "Failed to query discovery repository."
        return raw.substringBefore("\nURL:").substringBefore("\nHeaders:").trim()
    }
}
