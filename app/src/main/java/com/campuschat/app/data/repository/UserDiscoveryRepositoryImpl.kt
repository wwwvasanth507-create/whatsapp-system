package com.campuschat.app.data.repository

import android.util.Log
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

    private companion object {
        private const val TAG = "UserDiscoveryRepo"

        private fun safeLogD(message: String) {
            try {
                Log.d(TAG, message)
            } catch (_: Throwable) {
                println("[$TAG] DEBUG: $message")
            }
        }

        private fun safeLogE(message: String) {
            try {
                Log.e(TAG, message)
            } catch (_: Throwable) {
                println("[$TAG] ERROR: $message")
            }
        }
    }

    override suspend fun searchUsers(query: String): Resource<List<UserProfile>> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            safeLogD("Search query is blank, returning empty list.")
            return Resource.Success(emptyList())
        }
        safeLogD("Executing user discovery query (length=${trimmed.length}, query='$trimmed')")
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
            safeLogD("User discovery query returned ${resultList.size} profile(s).")
            Resource.Success(resultList)
        } catch (e: Exception) {
            val sanitized = sanitizeErrorMessage(e)
            safeLogE("User discovery failed: $sanitized")
            Resource.Error(sanitized, e)
        }
    }

    override suspend fun getRecipientDevices(userId: String): Resource<List<UserDevice>> {
        if (userId.isBlank()) {
            safeLogD("Recipient userId is blank, returning empty list.")
            return Resource.Success(emptyList())
        }
        safeLogD("Querying active devices for target user id length=${userId.length}")
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
                    platform = dto.platform,
                    registrationId = dto.registrationId
                )
            }
            safeLogD("Recipient device query returned ${deviceList.size} active device(s).")
            Resource.Success(deviceList)
        } catch (e: Exception) {
            val sanitized = sanitizeErrorMessage(e)
            safeLogE("Recipient device query failed: $sanitized")
            Resource.Error(sanitized, e)
        }
    }

    private fun sanitizeErrorMessage(e: Exception): String {
        val raw = e.message ?: return "Failed to query discovery repository."
        return raw.substringBefore("\nURL:").substringBefore("\nHeaders:").trim()
    }
}

