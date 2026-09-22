package com.campuschat.app.presentation.navigation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.campuschat.app.core.crypto.CampusChatSignalProtocolStore
import com.campuschat.app.core.crypto.CryptoKeyManagerImpl
import com.campuschat.app.core.crypto.IdentityKeyManagerImpl
import com.campuschat.app.core.crypto.PreKeyManagerImpl
import com.campuschat.app.core.crypto.SessionStoreImpl
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.network.SupabaseClientProvider
import com.campuschat.app.data.local.CampusChatDatabase
import com.campuschat.app.data.realtime.RealtimeMessageObserver
import com.campuschat.app.data.repository.AuthRepositoryImpl
import com.campuschat.app.data.repository.DeviceRepositoryImpl
import com.campuschat.app.data.repository.LocalChatRepositoryImpl
import com.campuschat.app.data.repository.MessageTransportRepositoryImpl
import com.campuschat.app.data.repository.PreKeySyncRepositoryImpl
import com.campuschat.app.data.repository.ProfileRepositoryImpl
import com.campuschat.app.data.repository.UserDiscoveryRepositoryImpl
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.service.EncryptedMessageService
import com.campuschat.app.domain.service.EncryptedMessageServiceImpl
import com.campuschat.app.domain.service.MessageOutboxService
import com.campuschat.app.domain.service.MessageOutboxServiceImpl
import com.campuschat.app.domain.service.PendingMessageService
import com.campuschat.app.domain.service.PendingMessageServiceImpl
import com.campuschat.app.domain.service.X3DHSessionService
import com.campuschat.app.domain.service.X3DHSessionServiceImpl
import com.campuschat.app.domain.usecase.GetProfileUseCase
import com.campuschat.app.domain.usecase.LoginUseCase
import com.campuschat.app.domain.usecase.LogoutUseCase
import com.campuschat.app.domain.usecase.RegisterUseCase
import com.campuschat.app.domain.usecase.RestoreSessionUseCase
import com.campuschat.app.presentation.auth.login.LoginViewModel
import com.campuschat.app.presentation.auth.register.RegisterViewModel
import com.campuschat.app.presentation.chat.conversation.ConversationViewModel
import com.campuschat.app.presentation.chat.newchat.NewChatViewModel
import com.campuschat.app.presentation.home.HomeViewModel
import com.campuschat.app.presentation.profile.ProfileViewModel
import com.campuschat.app.presentation.settings.SettingsViewModel
import com.campuschat.app.presentation.splash.SplashViewModel

object AppViewModelFactory {

    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    private val authRepository by lazy { AuthRepositoryImpl(SupabaseClientProvider.client) }
    private val profileRepository by lazy { ProfileRepositoryImpl(SupabaseClientProvider.client) }
    private val deviceRepository by lazy { DeviceRepositoryImpl(SupabaseClientProvider.client, registrationIdProvider = { protocolStore?.getLocalRegistrationId() ?: 1 }) }
    private val userDiscoveryRepository by lazy { UserDiscoveryRepositoryImpl(SupabaseClientProvider.client) }
    private val transportRepository by lazy { MessageTransportRepositoryImpl(SupabaseClientProvider.client) }

    private val loginUseCase by lazy { LoginUseCase(authRepository, profileRepository, deviceRepository, preKeySyncRepository) }
    private val registerUseCase by lazy { RegisterUseCase(authRepository, profileRepository, deviceRepository, preKeySyncRepository) }
    private val restoreSessionUseCase by lazy { RestoreSessionUseCase(authRepository, profileRepository, deviceRepository, preKeySyncRepository) }
    private val getProfileUseCase by lazy { GetProfileUseCase(profileRepository) }
    private val logoutUseCase by lazy { LogoutUseCase(authRepository) }

    val localChatRepository: LocalChatRepository? by lazy {
        applicationContext?.let { ctx ->
            try {
                val db = CampusChatDatabase.getInstance(ctx)
                LocalChatRepositoryImpl(db.conversationDao(), db.messageDao())
            } catch (e: Exception) {
                Log.e("AppViewModelFactory", "Failed to initialize Room Database", e)
                null
            }
        }
    }

    private val cryptoKeyManager by lazy {
        applicationContext?.let { CryptoKeyManagerImpl(it) }
    }

    private val identityKeyManager by lazy {
        cryptoKeyManager?.let { crypto ->
            IdentityKeyManagerImpl(applicationContext, crypto)
        }
    }

    private val preKeyManager by lazy {
        if (cryptoKeyManager != null && identityKeyManager != null) {
            PreKeyManagerImpl(applicationContext, cryptoKeyManager!!, identityKeyManager!!)
        } else null
    }

    private val sessionStore by lazy {
        cryptoKeyManager?.let { crypto ->
            val store = SessionStoreImpl(applicationContext, crypto)
            store.initStore(DeviceIdProvider.getDeviceId())
            store
        }
    }

    private val preKeySyncRepository by lazy {
        if (identityKeyManager != null && preKeyManager != null) {
            PreKeySyncRepositoryImpl(SupabaseClientProvider.client, identityKeyManager!!, preKeyManager!!)
        } else null
    }

    private val protocolStore by lazy {
        if (identityKeyManager != null && preKeyManager != null && sessionStore != null) {
            CampusChatSignalProtocolStore(
                identityKeyManager = identityKeyManager!!,
                preKeyManager = preKeyManager!!,
                sessionStore = sessionStore!!,
                localDeviceId = DeviceIdProvider.getDeviceId(),
                localRegistrationId = 1
            )
        } else null
    }

    val encryptedMessageService: EncryptedMessageService by lazy {
        if (protocolStore != null && sessionStore != null) {
            EncryptedMessageServiceImpl(
                authRepository = authRepository,
                protocolStore = protocolStore!!,
                sessionStore = sessionStore!!
            )
        } else {
            DummyEncryptedMessageService()
        }
    }

    val x3dhSessionService: X3DHSessionService? by lazy {
        if (preKeySyncRepository != null && protocolStore != null && sessionStore != null) {
            X3DHSessionServiceImpl(
                authRepository = authRepository,
                preKeySyncRepository = preKeySyncRepository!!,
                protocolStore = protocolStore!!,
                sessionStore = sessionStore!!
            )
        } else null
    }

    val outboxService: MessageOutboxService by lazy {
        MessageOutboxServiceImpl(
            authRepository = authRepository,
            encryptedMessageService = encryptedMessageService,
            transportRepository = transportRepository,
            localChatRepository = localChatRepository,
            x3dhSessionService = x3dhSessionService
        )
    }

    val pendingMessageService: PendingMessageService by lazy {
        PendingMessageServiceImpl(
            authRepository = authRepository,
            encryptedMessageService = encryptedMessageService,
            transportRepository = transportRepository,
            localChatRepository = localChatRepository
        )
    }

    val realtimeMessageObserver: RealtimeMessageObserver? by lazy {
        RealtimeMessageObserver(
            supabaseClient = SupabaseClientProvider.client,
            pendingMessageService = pendingMessageService
        )
    }

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
            return HomeViewModel(
                getProfileUseCase = getProfileUseCase,
                localChatRepository = localChatRepository
            ) as T
        }
    }

    fun provideNewChatViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NewChatViewModel(userDiscoveryRepository) as T
        }
    }

    fun provideConversationViewModelFactory(
        recipientUserId: String,
        recipientDeviceId: String
    ) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConversationViewModel(
                recipientUserId = recipientUserId,
                recipientDeviceId = recipientDeviceId,
                messageOutboxService = outboxService,
                pendingMessageService = pendingMessageService,
                x3dhSessionService = x3dhSessionService,
                localChatRepository = localChatRepository
            ) as T
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

    private class DummyEncryptedMessageService : EncryptedMessageService {
        override suspend fun encryptMessage(
            senderDeviceId: String,
            recipientUserId: String,
            recipientDeviceId: String,
            recipientRegistrationId: Int,
            plaintext: String
        ): com.campuschat.app.domain.model.EncryptionResult {
            return com.campuschat.app.domain.model.EncryptionResult.SessionNotFound
        }

        override suspend fun decryptMessage(
            envelope: com.campuschat.app.domain.model.EncryptedMessageEnvelope
        ): com.campuschat.app.domain.model.DecryptionResult {
            return com.campuschat.app.domain.model.DecryptionResult.SessionNotFound
        }
    }
}
