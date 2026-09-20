package com.campuschat.app.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.campuschat.app.core.network.SupabaseClientProvider
import com.campuschat.app.data.repository.AuthRepositoryImpl
import com.campuschat.app.data.repository.DeviceRepositoryImpl
import com.campuschat.app.data.repository.ProfileRepositoryImpl
import com.campuschat.app.domain.usecase.GetProfileUseCase
import com.campuschat.app.domain.usecase.LoginUseCase
import com.campuschat.app.domain.usecase.LogoutUseCase
import com.campuschat.app.domain.usecase.RegisterUseCase
import com.campuschat.app.domain.usecase.RestoreSessionUseCase
import com.campuschat.app.presentation.auth.login.LoginViewModel
import com.campuschat.app.presentation.auth.register.RegisterViewModel
import com.campuschat.app.presentation.home.HomeViewModel
import com.campuschat.app.presentation.profile.ProfileViewModel
import com.campuschat.app.presentation.settings.SettingsViewModel
import com.campuschat.app.presentation.splash.SplashViewModel

object AppViewModelFactory {

    private val authRepository by lazy { AuthRepositoryImpl(SupabaseClientProvider.client) }
    private val profileRepository by lazy { ProfileRepositoryImpl(SupabaseClientProvider.client) }
    private val deviceRepository by lazy { DeviceRepositoryImpl(SupabaseClientProvider.client) }

    private val loginUseCase by lazy { LoginUseCase(authRepository, profileRepository, deviceRepository) }
    private val registerUseCase by lazy { RegisterUseCase(authRepository, profileRepository, deviceRepository) }
    private val restoreSessionUseCase by lazy { RestoreSessionUseCase(authRepository, profileRepository, deviceRepository) }
    private val getProfileUseCase by lazy { GetProfileUseCase(profileRepository) }
    private val logoutUseCase by lazy { LogoutUseCase(authRepository) }

    fun provideSplashViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SplashViewModel(restoreSessionUseCase) as T
        }
    }

    fun provideLoginViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(loginUseCase) as T
        }
    }

    fun provideRegisterViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RegisterViewModel(registerUseCase) as T
        }
    }

    fun provideHomeViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(getProfileUseCase) as T
        }
    }

    fun provideProfileViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ProfileViewModel(getProfileUseCase) as T
        }
    }

    fun provideSettingsViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(logoutUseCase) as T
        }
    }
}
