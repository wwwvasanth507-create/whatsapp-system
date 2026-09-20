package com.campuschat.app.domain.usecase

import android.util.Log
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.result.Resource
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.DeviceRepository
import com.campuschat.app.domain.repository.ProfileRepository

class LoginUseCase(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository
) {
    suspend operator fun invoke(email: String, password: String): Resource<UserProfile> {
        val trimmedEmail = email.trim()

        if (trimmedEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return Resource.Error("Please enter a valid email address.")
        }
        if (password.isEmpty()) {
            return Resource.Error("Password cannot be empty.")
        }

        // 1. Supabase Auth Login
        return when (val authResult = authRepository.signIn(trimmedEmail, password)) {
            is Resource.Error -> Resource.Error(authResult.message, authResult.cause)
            is Resource.Loading -> Resource.Loading
            is Resource.Idle -> Resource.Idle
            is Resource.Success -> {
                val user = authResult.data
                val userId = user.id

                // 2. Fetch User Profile
                val profileResult = profileRepository.getProfile(userId)
                val userProfile = (profileResult as? Resource.Success)?.data ?: UserProfile(
                    id = userId,
                    username = trimmedEmail.substringBefore("@"),
                    displayName = trimmedEmail.substringBefore("@")
                )

                // 3. Register / Update Device Info in devices table
                val deviceId = DeviceIdProvider.getDeviceId()
                val deviceName = DeviceIdProvider.getDeviceName()
                val device = UserDevice(
                    id = deviceId,
                    userId = userId,
                    deviceName = deviceName,
                    platform = DeviceIdProvider.getPlatform()
                )
                val deviceResult = deviceRepository.registerOrUpdateDevice(device)
                if (deviceResult is Resource.Error) {
                    Log.e("LoginUseCase", "Device registration non-fatal error: ${deviceResult.message}")
                }

                val lastSeenResult = deviceRepository.updateLastSeen(userId, deviceId)
                if (lastSeenResult is Resource.Error) {
                    Log.e("LoginUseCase", "Update last_seen_at non-fatal error: ${lastSeenResult.message}")
                }

                // 4. Update Session state
                SessionManager.setAuthenticated(user)

                Resource.Success(userProfile)
            }
        }
    }
}
