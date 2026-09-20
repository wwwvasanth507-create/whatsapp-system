package com.campuschat.app.data.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.dto.ProfileDto
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.ProfileRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class ProfileRepositoryImpl(
    private val supabaseClient: SupabaseClient
) : ProfileRepository {

    override suspend fun createProfile(userId: String, username: String, displayName: String): Resource<UserProfile> {
        return try {
            val dto = ProfileDto(
                id = userId,
                username = username,
                displayName = displayName
            )
            supabaseClient.postgrest["profiles"].upsert(dto)
            val profile = UserProfile(
                id = userId,
                username = username,
                displayName = displayName
            )
            Resource.Success(profile)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Failed to create profile.", e)
        }
    }

    override suspend fun getProfile(userId: String): Resource<UserProfile> {
        return try {
            val result = supabaseClient.postgrest["profiles"]
                .select {
                    filter {
                        eq("id", userId)
                    }
                }.decodeSingle<ProfileDto>()
            val profile = UserProfile(
                id = result.id,
                username = result.username ?: "",
                displayName = result.displayName ?: result.username ?: "Campus User",
                avatarUrl = result.avatarUrl,
                createdAt = result.createdAt,
                updatedAt = result.updatedAt
            )
            Resource.Success(profile)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Failed to load user profile.", e)
        }
    }

    override suspend fun updateProfile(profile: UserProfile): Resource<UserProfile> {
        return try {
            val dto = ProfileDto(
                id = profile.id,
                username = profile.username,
                displayName = profile.displayName,
                avatarUrl = profile.avatarUrl
            )
            supabaseClient.postgrest["profiles"].update(dto) {
                filter {
                    eq("id", profile.id)
                }
            }
            Resource.Success(profile)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Failed to update profile.", e)
        }
    }
}
