package com.campuschat.app.domain.usecase

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.ProfileRepository

class GetProfileUseCase(
    private val profileRepository: ProfileRepository
) {
    suspend operator fun invoke(userId: String): Resource<UserProfile> {
        return profileRepository.getProfile(userId)
    }
}
