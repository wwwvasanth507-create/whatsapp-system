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

class RegisterUseCase(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val preKeySyncRepository: PreKeySyncRepository? = null
) {
    suspend operator fun invoke(
        email: String,
        password: String,
        username: String,
        displayName: String
    ): Resource<UserProfile> {
        // Validation rules
        val trimmedEmail = email.trim()
        val trimmedUsername = username.trim()
        val trimmedDisplayName = displayName.trim()

        if (trimmedEmail.isEmpty() || !isValidEmail(trimmedEmail)) {
            return Resource.Error("Please enter a valid email address.")
        }
        if (password.length < 6) {
            return Resource.Error("Password must be at least 6 characters long.")
        }
        if (trimmedUsername.length < 3) {
            return Resource.Error("Username must be at least 3 characters long.")
        }
        if (trimmedDisplayName.isEmpty()) {
            return Resource.Error("Display name cannot be empty.")
        }

        // 1. Supabase Auth Registration
        return when (val authResult = authRepository.signUp(trimmedEmail, password)) {
            is Resource.Error -> Resource.Error(authResult.message, authResult.cause)
            is Resource.Loading -> Resource.Loading
            is Resource.Idle -> Resource.Idle
            is Resource.Success -> {
                val user = authResult.data
                val userId = user.id

                // 2. Ensure active JWT session for RLS authorization
                if (authRepository.getCurrentUser() == null) {
                    val signInRes = authRepository.signIn(trimmedEmail, password)
                    if (signInRes is Resource.Error) {
                        return Resource.Error("Account created. Please sign in with your email and password.")
                    }
                }

                // 3. Profile Creation in profiles table
                val profileResult = profileRepository.createProfile(userId, trimmedUsername, trimmedDisplayName)
                if (profileResult is Resource.Error) {
                    return Resource.Error("Auth succeeded, but profile creation failed: ${profileResult.message}")
                }

                // 4. Device Registration in devices table
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
                    return Resource.Error("Profile created, but device registration failed: ${deviceResult.message}")
                }
                val registeredDevice = (deviceResult as? Resource.Success)?.data
                val regId = registeredDevice?.registrationId ?: 1

                // 5. Publish Signal Public Keys to Supabase
                if (preKeySyncRepository != null) {
                    val syncResult = preKeySyncRepository.publishLocalPublicKeys(userId, deviceId, regId)
                    if (syncResult is Resource.Error) {
                        Log.e("RegisterUseCase", "Signal public key publishing non-fatal error: ${syncResult.message}")
                    }
                }

                // 6. Update Session Manager
                SessionManager.setAuthenticated(user)

                val finalProfile = (profileResult as? Resource.Success)?.data ?: UserProfile(
                    id = userId,
                    username = trimmedUsername,
                    displayName = trimmedDisplayName
                )
                Resource.Success(finalProfile)
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
