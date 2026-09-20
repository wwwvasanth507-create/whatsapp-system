package com.campuschat.app.domain.usecase

import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.result.Resource
import com.campuschat.app.core.session.AuthState
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.DeviceRepository
import com.campuschat.app.domain.repository.ProfileRepository

class RestoreSessionUseCase(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository
) {
    suspend operator fun invoke(): Resource<UserProfile?> {
        val authState = SessionManager.checkSession()
        return if (authState is AuthState.Authenticated) {
            val userId = authState.user.id
            val deviceId = DeviceIdProvider.getDeviceId()
            deviceRepository.updateLastSeen(userId, deviceId)

            when (val profileRes = profileRepository.getProfile(userId)) {
                is Resource.Success -> Resource.Success(profileRes.data)
                else -> Resource.Success(null)
            }
        } else {
            Resource.Success(null)
        }
    }
}
