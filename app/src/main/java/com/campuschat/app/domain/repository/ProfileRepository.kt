package com.campuschat.app.domain.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.UserProfile

interface ProfileRepository {
    suspend fun createProfile(userId: String, username: String, displayName: String): Resource<UserProfile>
    suspend fun getProfile(userId: String): Resource<UserProfile>
    suspend fun updateProfile(profile: UserProfile): Resource<UserProfile>
}
