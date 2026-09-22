package com.campuschat.app.presentation.navigation

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.MessageTransportRepository
import com.campuschat.app.domain.repository.UserDiscoveryRepository
import com.campuschat.app.domain.service.EncryptedMessageService
import com.campuschat.app.domain.service.MessageOutboxServiceImpl
import com.campuschat.app.domain.service.PendingMessageServiceImpl
import com.campuschat.app.domain.usecase.GetProfileUseCase
import com.campuschat.app.presentation.chat.conversation.ConversationViewModel
import com.campuschat.app.presentation.chat.newchat.NewChatViewModel
import com.campuschat.app.presentation.home.HomeViewModel
import io.github.jan.supabase.gotrue.user.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatShellNavigationTest {

    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Home empty state
    @Test
    fun test1_HomeEmptyState() = runTest(testDispatcher) {
        val dummyProfileRepo = object : com.campuschat.app.domain.repository.ProfileRepository {
            override suspend fun createProfile(userId: String, username: String, displayName: String) = Resource.Success(UserProfile(userId, username, displayName))
            override suspend fun getProfile(userId: String) = Resource.Success(UserProfile("u1", "user1", "User One"))
            override suspend fun updateProfile(profile: UserProfile) = Resource.Success(profile)
        }
        val getProfileUseCase = GetProfileUseCase(dummyProfileRepo)
        val homeViewModel = HomeViewModel(getProfileUseCase)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = homeViewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.conversations.isEmpty())
        assertNull(state.errorMessage)
    }

    // 2. Navigation to New Chat route definition
    @Test
    fun test2_NavigationToNewChat() {
        val route = Screen.NewChat.route
        assertEquals("new_chat", route)
    }

    // 3. Navigation to Conversation route parameter generation
    @Test
    fun test3_NavigationToConversation() {
        val recipientUserId = "user-123"
        val recipientDeviceId = "device-456"
        val generatedRoute = Screen.Conversation.createRoute(recipientUserId, recipientDeviceId)

        assertEquals("conversation/user-123/device-456", generatedRoute)
        assertEquals("conversation/{recipientUserId}/{recipientDeviceId}", Screen.Conversation.route)
    }

    // 4. Conversation empty state
    @Test
    fun test4_ConversationEmptyState() = runTest(testDispatcher) {
        val dummyAuth = object : AuthRepository {
            override suspend fun signUp(email: String, password: String) = Resource.Error("Not implemented")
            override suspend fun signIn(email: String, password: String) = Resource.Error("Not implemented")
            override suspend fun signOut() = Resource.Success(Unit)
            override suspend fun getCurrentUser(): UserInfo? = null
            override suspend fun restoreSession() = Resource.Success(null)
        }
        val dummyTransport = object : MessageTransportRepository {
            override suspend fun enqueueEncryptedMessage(envelope: com.campuschat.app.domain.model.EncryptedMessageEnvelope, ttlSeconds: Int) = Resource.Error("Not implemented")
            override suspend fun fetchPendingMessages(deviceId: String): Resource<List<com.campuschat.app.domain.model.PendingTransportMessage>> = Resource.Success(emptyList())
            override suspend fun acknowledgeMessage(messageId: String) = Resource.Success(true)
            override suspend fun deleteDeliveredMessage(messageId: String) = Resource.Success(true)
        }
        val dummyCrypto = object : EncryptedMessageService {
            override suspend fun encryptMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String) = com.campuschat.app.domain.model.EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: com.campuschat.app.domain.model.EncryptedMessageEnvelope) = com.campuschat.app.domain.model.DecryptionResult.SessionNotFound
        }

        val outbox = MessageOutboxServiceImpl(dummyAuth, dummyCrypto, dummyTransport)
        val pending = PendingMessageServiceImpl(dummyAuth, dummyCrypto, dummyTransport)

        val conversationViewModel = ConversationViewModel(
            recipientUserId = "target-user-999",
            recipientDeviceId = "target-device-888",
            messageOutboxService = outbox,
            pendingMessageService = pending
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val state = conversationViewModel.uiState.value
        assertEquals("target-user-999", state.recipientUserId)
        assertEquals("target-device-888", state.recipientDeviceId)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.messageInput.isEmpty())
        assertFalse(state.isSending)
    }

    // 5. Send button disabled when message is empty
    @Test
    fun test5_SendButtonDisabledWhenMessageIsEmpty() = runTest(testDispatcher) {
        val dummyAuth = object : AuthRepository {
            override suspend fun signUp(email: String, password: String) = Resource.Error("Not implemented")
            override suspend fun signIn(email: String, password: String) = Resource.Error("Not implemented")
            override suspend fun signOut() = Resource.Success(Unit)
            override suspend fun getCurrentUser(): UserInfo? = null
            override suspend fun restoreSession() = Resource.Success(null)
        }
        val dummyTransport = object : MessageTransportRepository {
            override suspend fun enqueueEncryptedMessage(envelope: com.campuschat.app.domain.model.EncryptedMessageEnvelope, ttlSeconds: Int) = Resource.Error("Not implemented")
            override suspend fun fetchPendingMessages(deviceId: String): Resource<List<com.campuschat.app.domain.model.PendingTransportMessage>> = Resource.Success(emptyList())
            override suspend fun acknowledgeMessage(messageId: String) = Resource.Success(true)
            override suspend fun deleteDeliveredMessage(messageId: String) = Resource.Success(true)
        }
        val dummyCrypto = object : EncryptedMessageService {
            override suspend fun encryptMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String) = com.campuschat.app.domain.model.EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: com.campuschat.app.domain.model.EncryptedMessageEnvelope) = com.campuschat.app.domain.model.DecryptionResult.SessionNotFound
        }

        val outbox = MessageOutboxServiceImpl(dummyAuth, dummyCrypto, dummyTransport)
        val pending = PendingMessageServiceImpl(dummyAuth, dummyCrypto, dummyTransport)

        val conversationViewModel = ConversationViewModel(
            recipientUserId = "target-user-999",
            recipientDeviceId = "target-device-888",
            messageOutboxService = outbox,
            pendingMessageService = pending
        )

        // Blank input -> send should be rejected
        conversationViewModel.onMessageInputChanged("   ")
        assertTrue(conversationViewModel.uiState.value.messageInput.trim().isEmpty())

        conversationViewModel.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(conversationViewModel.uiState.value.messages.isEmpty())
    }

    // 6. Back navigation screen route hierarchy
    @Test
    fun test6_BackNavigationRoutes() {
        val allRoutes = listOf(
            Screen.Splash.route,
            Screen.Login.route,
            Screen.Register.route,
            Screen.Home.route,
            Screen.NewChat.route,
            Screen.Profile.route,
            Screen.Settings.route,
            Screen.Conversation.route
        )

        assertEquals(8, allRoutes.size)
        assertTrue(allRoutes.contains("new_chat"))
        assertTrue(allRoutes.contains("conversation/{recipientUserId}/{recipientDeviceId}"))
    }
}
