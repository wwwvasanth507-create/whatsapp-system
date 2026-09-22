package com.campuschat.app.presentation

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.local.entity.ConversationEntity
import com.campuschat.app.data.local.entity.MessageEntity
import com.campuschat.app.data.realtime.RealtimeMessageObserver
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.PendingTransportMessage
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.model.X3DHSessionResult
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.repository.UserDiscoveryRepository
import com.campuschat.app.domain.service.MessageOutboxService
import com.campuschat.app.domain.service.PendingMessageService
import com.campuschat.app.domain.service.ProcessPendingResult
import com.campuschat.app.domain.service.ReceivedMessageResult
import com.campuschat.app.domain.service.X3DHSessionService
import com.campuschat.app.presentation.chat.conversation.ConversationViewModel
import com.campuschat.app.presentation.chat.newchat.NewChatViewModel
import com.campuschat.app.presentation.home.HomeViewModel
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import io.github.jan.supabase.gotrue.user.UserInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

private fun createTestUserInfo(userId: String, email: String): UserInfo {
    val jsonStr = """
    {
        "id": "$userId",
        "aud": "authenticated",
        "role": "authenticated",
        "email": "$email",
        "email_confirmed_at": "2026-09-20T00:00:00Z",
        "phone": "",
        "confirmed_at": "2026-09-20T00:00:00Z",
        "last_sign_in_at": "2026-09-20T00:00:00Z",
        "app_metadata": {},
        "user_metadata": {},
        "identities": [],
        "created_at": "2026-09-20T00:00:00Z",
        "updated_at": "2026-09-20T00:00:00Z"
    }
    """.trimIndent()
    return kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<UserInfo>(jsonStr)
}

@OptIn(ExperimentalCoroutinesApi::class)
class UiAndMessagingIntegrationTest {

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
    fun test01_HomeEmptyState() = runBlocking {
        val fakeRepo = FakeLocalChatRepository()
        val fakeProfileRepo = object : com.campuschat.app.domain.repository.ProfileRepository {
            override suspend fun createProfile(userId: String, username: String, displayName: String): Resource<UserProfile> {
                return Resource.Success(UserProfile(userId, username, displayName, null))
            }
            override suspend fun getProfile(userId: String): Resource<UserProfile> {
                return Resource.Success(UserProfile("u1", "alice", "Alice", null))
            }
            override suspend fun updateProfile(profile: UserProfile): Resource<UserProfile> {
                return Resource.Success(profile)
            }
        }
        val viewModel = HomeViewModel(
            getProfileUseCase = com.campuschat.app.domain.usecase.GetProfileUseCase(fakeProfileRepo),
            localChatRepository = fakeRepo
        )

        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state.conversations.isEmpty())
    }

    // 2. Conversation list rendering
    @Test
    fun test02_ConversationListRendering() = runBlocking {
        val fakeRepo = FakeLocalChatRepository()
        fakeRepo.saveConversation(
            ConversationEntity(
                id = "alice_bob_dev1",
                localAccountId = "alice",
                recipientUserId = "bob",
                recipientDeviceId = "dev1",
                recipientUsername = "bob_user",
                recipientDisplayName = "Bob",
                lastMessageSnippet = "Hello Alice",
                lastMessageTimestamp = System.currentTimeMillis(),
                unreadCount = 2
            )
        )

        val fakeProfileRepo = object : com.campuschat.app.domain.repository.ProfileRepository {
            override suspend fun createProfile(userId: String, username: String, displayName: String): Resource<UserProfile> {
                return Resource.Success(UserProfile(userId, username, displayName, null))
            }
            override suspend fun getProfile(userId: String): Resource<UserProfile> {
                return Resource.Success(UserProfile("u1", "alice", "Alice", null))
            }
            override suspend fun updateProfile(profile: UserProfile): Resource<UserProfile> {
                return Resource.Success(profile)
            }
        }

        val viewModel = HomeViewModel(
            getProfileUseCase = com.campuschat.app.domain.usecase.GetProfileUseCase(fakeProfileRepo),
            localChatRepository = fakeRepo
        )

        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(1, state.conversations.size)
        assertEquals("Bob", state.conversations[0].recipientDisplayName)
        assertEquals("bob_user", state.conversations[0].recipientUsername)
        assertEquals(2, state.conversations[0].unreadCount)
    }

    // 3. New Chat search & User selection
    @Test
    fun test03_NewChatSearchAndUserSelection() = runBlocking {
        val discoveryRepo = object : UserDiscoveryRepository {
            override suspend fun searchUsers(query: String): Resource<List<UserProfile>> {
                return Resource.Success(
                    listOf(UserProfile("bob_id", "bob_user", "Bob Builder", null))
                )
            }

            override suspend fun getRecipientDevices(userId: String): Resource<List<UserDevice>> {
                return Resource.Success(
                    listOf(UserDevice("dev_b1", "bob_id", "Pixel 8", "android"))
                )
            }
        }

        val viewModel = NewChatViewModel(discoveryRepo)
        viewModel.onSearchQueryChanged("bob")

        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(1, state.searchResults.size)
        assertEquals("Bob Builder", state.searchResults[0].profile.displayName)
    }

    // 4. Device selection & multi-device isolation
    @Test
    fun test04_MultiDeviceIsolationInDiscovery() = runBlocking {
        val discoveryRepo = object : UserDiscoveryRepository {
            override suspend fun searchUsers(query: String): Resource<List<UserProfile>> {
                return Resource.Success(listOf(UserProfile("bob_id", "bob", "Bob", null)))
            }

            override suspend fun getRecipientDevices(userId: String): Resource<List<UserDevice>> {
                return Resource.Success(
                    listOf(
                        UserDevice("dev_1", "bob_id", "Device 1", "android"),
                        UserDevice("dev_2", "bob_id", "Device 2", "android")
                    )
                )
            }
        }

        val viewModel = NewChatViewModel(discoveryRepo)
        viewModel.onSearchQueryChanged("bob")

        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(2, state.searchResults[0].devices.size)
        assertEquals("dev_1", state.searchResults[0].devices[0].id)
        assertEquals("dev_2", state.searchResults[0].devices[1].id)
    }

    // 5. Blank message rejection
    @Test
    fun test05_BlankMessageRejection() = runBlocking {
        val mockOutbox = mock(MessageOutboxService::class.java)
        val mockPending = mock(PendingMessageService::class.java)
        val viewModel = ConversationViewModel(
            recipientUserId = "bob",
            recipientDeviceId = "dev1",
            messageOutboxService = mockOutbox,
            pendingMessageService = mockPending
        )

        viewModel.onMessageInputChanged("   ")
        viewModel.sendMessage()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
    }

    // 6. Send success & Session establishment before first message
    @Test
    fun test06_SendSuccessWithX3DHSessionEstablishment() = runBlocking {
        var x3dhCalled = false
        var outboxCalled = false

        val fakeX3DH = object : X3DHSessionService {
            override suspend fun establishOutboundSession(recipientDeviceId: String): X3DHSessionResult {
                x3dhCalled = true
                return X3DHSessionResult.SessionEstablished(
                    address = org.signal.libsignal.protocol.SignalProtocolAddress("bob", 1),
                    remoteDeviceId = recipientDeviceId,
                    claimedOneTimePreKeyId = null
                )
            }
        }

        val fakeOutbox = object : MessageOutboxService {
            override suspend fun queueTextMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String): MessageEntity? = null
            override suspend fun processPendingOutboxMessages(localAccountId: String, senderDeviceId: String): Resource<Int> = Resource.Success(0)
            override suspend fun sendEncryptedTextMessage(
                senderDeviceId: String,
                recipientUserId: String,
                recipientDeviceId: String,
                recipientRegistrationId: Int,
                plaintext: String
            ): Resource<String> {
                outboxCalled = true
                return Resource.Success("msg_123")
            }
        }

        val fakePending = object : PendingMessageService {
            override suspend fun fetchAndDecryptPendingMessages(localDeviceId: String): ProcessPendingResult {
                return ProcessPendingResult.Processed(emptyList(), 0)
            }
        }

        val viewModel = ConversationViewModel(
            recipientUserId = "bob",
            recipientDeviceId = "dev1",
            messageOutboxService = fakeOutbox,
            pendingMessageService = fakePending,
            x3dhSessionService = fakeX3DH
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onMessageInputChanged("Hello Bob")
        viewModel.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(x3dhCalled)
        assertTrue(outboxCalled)
        assertEquals("", viewModel.uiState.value.messageInput)
    }

    // 7. Send failure handling without exposing sensitive info
    @Test
    fun test07_SendFailureHandling() = runBlocking {
        val fakeOutbox = object : MessageOutboxService {
            override suspend fun queueTextMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String): MessageEntity? = null
            override suspend fun processPendingOutboxMessages(localAccountId: String, senderDeviceId: String): Resource<Int> = Resource.Success(0)
            override suspend fun sendEncryptedTextMessage(
                senderDeviceId: String,
                recipientUserId: String,
                recipientDeviceId: String,
                recipientRegistrationId: Int,
                plaintext: String
            ): Resource<String> {
                return Resource.Error("Transport network unavailable")
            }
        }

        val fakePending = object : PendingMessageService {
            override suspend fun fetchAndDecryptPendingMessages(localDeviceId: String): ProcessPendingResult {
                return ProcessPendingResult.Processed(emptyList(), 0)
            }
        }

        val viewModel = ConversationViewModel(
            recipientUserId = "bob",
            recipientDeviceId = "dev1",
            messageOutboxService = fakeOutbox,
            pendingMessageService = fakePending
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onMessageInputChanged("Hello Bob")
        viewModel.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Transport network unavailable", state.errorMessage)
    }

    // 8. Room Flow observation for received messages
    @Test
    fun test08_RoomFlowObservation() = runBlocking {
        com.campuschat.app.core.session.SessionManager.setAuthenticated(
            createTestUserInfo("alice", "alice@example.com")
        )
        val fakeRepo = FakeLocalChatRepository()
        val fakeOutbox = mock(MessageOutboxService::class.java)
        val fakePending = mock(PendingMessageService::class.java)

        val viewModel = ConversationViewModel(
            recipientUserId = "bob",
            recipientDeviceId = "dev1",
            messageOutboxService = fakeOutbox,
            pendingMessageService = fakePending,
            localChatRepository = fakeRepo
        )

        testDispatcher.scheduler.advanceUntilIdle()

        fakeRepo.saveMessage(
            MessageEntity(
                id = "msg_rcv_1",
                localAccountId = "alice",
                conversationId = "alice_bob_dev1",
                senderUserId = "bob",
                senderDeviceId = "dev1",
                recipientUserId = "alice",
                recipientDeviceId = "alice_dev",
                direction = "RECEIVED",
                content = "Hi Alice!",
                timestamp = System.currentTimeMillis(),
                deliveryState = "DELIVERED",
                readState = true
            )
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.messages.size)
        assertEquals("Hi Alice!", state.messages[0].plaintext)
        assertFalse(state.messages[0].isFromSelf)
    }

    // 9. Duplicate message handling in Room Repo
    @Test
    fun test09_DuplicateMessageNoDuplicateRow() = runBlocking {
        val fakeRepo = FakeLocalChatRepository()
        val msg = MessageEntity(
            id = "msg_dup_1",
            localAccountId = "alice",
            conversationId = "alice_bob_dev1",
            senderUserId = "bob",
            senderDeviceId = "dev1",
            recipientUserId = "alice",
            recipientDeviceId = "alice_dev",
            direction = "RECEIVED",
            content = "Duplicate test",
            timestamp = System.currentTimeMillis(),
            deliveryState = "DELIVERED",
            readState = true
        )

        fakeRepo.saveMessage(msg)
        fakeRepo.saveMessage(msg)

        val messages = fakeRepo.getMessagesForConversation("alice", "bob", "dev1").first()
        assertEquals(1, messages.size)
    }

    // 10. RealtimeObserver duplicate subscription protection
    @Test
    fun test10_RealtimeObserverSingleSubscription() {
        val fakeClient = mock(SupabaseClient::class.java)
        val fakePending = mock(PendingMessageService::class.java)
        val observer = RealtimeMessageObserver(fakeClient, fakePending)

        assertFalse(observer.isObserving)
        observer.startObserving()
        val stateFirst = observer.isObserving
        observer.startObserving() // Duplicate call
        val stateSecond = observer.isObserving

        assertEquals(stateFirst, stateSecond)
        observer.stopObserving()
        assertFalse(observer.isObserving)
    }
}

// Fake in-memory implementation of LocalChatRepository for UI unit tests
private class FakeLocalChatRepository : LocalChatRepository {
    private val conversationsFlow = MutableStateFlow<Map<String, ConversationEntity>>(emptyMap())
    private val messagesFlow = MutableStateFlow<Map<String, MessageEntity>>(emptyMap())

    override fun getAllConversations(localAccountId: String): Flow<List<ConversationEntity>> {
        return conversationsFlow.map { map ->
            map.values.filter { localAccountId.isBlank() || it.localAccountId == localAccountId }
                .sortedByDescending { it.lastMessageTimestamp }
        }
    }

    override suspend fun getConversation(
        localAccountId: String,
        recipientUserId: String,
        recipientDeviceId: String
    ): ConversationEntity? {
        val targetConvId = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
        return conversationsFlow.value[targetConvId]
    }

    override fun getMessagesForConversation(
        localAccountId: String,
        recipientUserId: String,
        recipientDeviceId: String
    ): Flow<List<MessageEntity>> {
        val targetConvId = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
        return messagesFlow.map { map ->
            map.values.filter { it.conversationId == targetConvId && (localAccountId.isBlank() || it.localAccountId == localAccountId) }
                .sortedBy { it.timestamp }
        }
    }

    override suspend fun saveMessage(message: MessageEntity) {
        val currentMsgs = messagesFlow.value.toMutableMap()
        currentMsgs[message.id] = message
        messagesFlow.value = currentMsgs

        val currentConvs = conversationsFlow.value.toMutableMap()
        val convId = message.conversationId
        val existing = currentConvs[convId]
        val snippet = message.content
        val ts = message.timestamp
        val targetRecipientUserId = if (message.direction == "SENT") message.recipientUserId else message.senderUserId
        val targetRecipientDeviceId = if (message.direction == "SENT") message.recipientDeviceId else message.senderDeviceId

        val updatedConv = ConversationEntity(
            id = convId,
            localAccountId = message.localAccountId,
            recipientUserId = targetRecipientUserId,
            recipientDeviceId = targetRecipientDeviceId,
            recipientUsername = existing?.recipientUsername ?: "user",
            recipientDisplayName = existing?.recipientDisplayName ?: "Campus User",
            lastMessageSnippet = snippet,
            lastMessageTimestamp = ts,
            unreadCount = if (message.direction == "RECEIVED") (existing?.unreadCount ?: 0) + 1 else 0
        )
        currentConvs[convId] = updatedConv
        conversationsFlow.value = currentConvs
    }

    override suspend fun saveConversation(conversation: ConversationEntity) {
        val currentConvs = conversationsFlow.value.toMutableMap()
        currentConvs[conversation.id] = conversation
        conversationsFlow.value = currentConvs
    }

    override suspend fun updateMessageDeliveryState(messageId: String, deliveryState: String) {
        val currentMsgs = messagesFlow.value.toMutableMap()
        val existing = currentMsgs[messageId] ?: return
        currentMsgs[messageId] = existing.copy(deliveryState = deliveryState)
        messagesFlow.value = currentMsgs
    }

    override suspend fun getMessageById(messageId: String): MessageEntity? {
        return messagesFlow.value[messageId]
    }

    override suspend fun getPendingOutboundMessages(localAccountId: String): List<MessageEntity> {
        return messagesFlow.value.values.filter {
            it.localAccountId == localAccountId && it.deliveryState in listOf("QUEUED", "PENDING", "FAILED")
        }.sortedBy { it.timestamp }
    }

    override suspend fun clearChatHistory(localAccountId: String, recipientUserId: String, recipientDeviceId: String) {
        val currentMsgs = messagesFlow.value.toMutableMap()
        val keysToRemove = currentMsgs.filterValues {
            (localAccountId.isBlank() || it.localAccountId == localAccountId) &&
            it.recipientUserId == recipientUserId && it.recipientDeviceId == recipientDeviceId
        }.keys
        keysToRemove.forEach { currentMsgs.remove(it) }
        messagesFlow.value = currentMsgs
    }

    override suspend fun deleteConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String) {
        clearChatHistory(localAccountId, recipientUserId, recipientDeviceId)
        val currentConvs = conversationsFlow.value.toMutableMap()
        val keysToRemove = currentConvs.filterValues {
            (localAccountId.isBlank() || it.localAccountId == localAccountId) &&
            it.recipientUserId == recipientUserId && it.recipientDeviceId == recipientDeviceId
        }.keys
        keysToRemove.forEach { currentConvs.remove(it) }
        conversationsFlow.value = currentConvs
    }
}
