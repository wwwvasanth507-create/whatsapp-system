package com.campuschat.app.domain.usecase

import android.util.Log
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.result.Resource
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.DeviceRepository
import com.campuschat.app.domain.repository.PreKeySyncRepository
import com.campuschat.app.domain.repository.ProfileRepository

class LoginUseCase(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val preKeySyncRepository: PreKeySyncRepository? = null
) {
    suspend operator fun invoke(email: String, password: String): Resource<UserProfile> {
        val trimmedEmail = email.trim()

        if (trimmedEmail.isEmpty() || !isValidEmail(trimmedEmail)) {
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
                val regId = (deviceResult as? Resource.Success)?.data?.registrationId ?: 1
                if (deviceResult is Resource.Error) {
                    Log.e("LoginUseCase", "Device registration non-fatal error: ${deviceResult.message}")
                }

                val lastSeenResult = deviceRepository.updateLastSeen(userId, deviceId)
                if (lastSeenResult is Resource.Error) {
                    Log.e("LoginUseCase", "Update last_seen_at non-fatal error: ${lastSeenResult.message}")
                }

                // 4. Publish Signal Public Keys to Supabase
                if (preKeySyncRepository != null) {
                    val syncResult = preKeySyncRepository.publishLocalPublicKeys(userId, deviceId, regId)
                    if (syncResult is Resource.Error) {
                        Log.e("LoginUseCase", "Signal public key publishing non-fatal error: ${syncResult.message}")
                    }
                }

                // 5. Update Session state
                SessionManager.setAuthenticated(user)

                // 6. Start Realtime observer, fetch pending messages, process outbox queue
                try {
                    com.campuschat.app.presentation.navigation.AppViewModelFactory.realtimeMessageObserver?.startObserving()
                    com.campuschat.app.presentation.navigation.AppViewModelFactory.pendingMessageService.fetchAndDecryptPendingMessages(deviceId)
                    com.campuschat.app.presentation.navigation.AppViewModelFactory.outboxService.processPendingOutboxMessages(userId, deviceId)
                } catch (e: Exception) {
                    // Non-fatal sync startup exception
                }

                Resource.Success(userProfile)
            }
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return try {
            android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
        } catch (e: Throwable) {
            email.contains("@") && email.contains(".")
        }
    }
}
