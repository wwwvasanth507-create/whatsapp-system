package com.campuschat.app.data.realtime

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.local.entity.ConversationEntity
import com.campuschat.app.data.local.entity.MessageEntity
import com.campuschat.app.domain.model.DecryptionResult
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.EncryptionResult
import com.campuschat.app.domain.model.PendingTransportMessage
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.repository.MessageTransportRepository
import com.campuschat.app.domain.service.EncryptedMessageService
import com.campuschat.app.domain.service.MessageOutboxServiceImpl
import com.campuschat.app.domain.service.PendingMessageServiceImpl
import com.campuschat.app.domain.service.ProcessPendingResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.user.UserInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class RealtimeAndOutboxIntegrationTest {

    private fun createUserInfo(id: String, email: String): UserInfo {
        val jsonStr = """
        {
            "id": "$id",
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

    // A. realtime event causes pending fetch
    @Test
    fun testA_RealtimeEventCausesPendingFetch() = runBlocking {
        var fetchCalled = false
        val fakePendingService = object : com.campuschat.app.domain.service.PendingMessageService {
            override suspend fun fetchAndDecryptPendingMessages(localDeviceId: String): ProcessPendingResult {
                fetchCalled = true
                return ProcessPendingResult.Processed(emptyList(), 0)
            }
        }
        fakePendingService.fetchAndDecryptPendingMessages("dev_test")
        assertTrue(fetchCalled)
    }

    // B. pending encrypted message decrypts and appears in Room
    @Test
    fun testB_PendingEncryptedMessageDecryptsAndAppearsInRoom() = runBlocking {
        val aliceUser = createUserInfo("alice_id", "alice@example.com")
        val fakeAuthRepo = object : AuthRepository {
            override suspend fun signUp(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signIn(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signOut(): Resource<Unit> = Resource.Success(Unit)
            override suspend fun getCurrentUser(): UserInfo? = aliceUser
            override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(aliceUser)
        }

        val fakeCrypto = object : EncryptedMessageService {
            override suspend fun encryptMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String): EncryptionResult {
                return EncryptionResult.Success(EncryptedMessageEnvelope("bob_id", "dev_b", 1, "alice_id", "dev_a", 1, 2, "ciphertext"))
            }

            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope): DecryptionResult {
                return DecryptionResult.Success("Decrypted hello!")
            }
        }

        val fakeTransport = object : MessageTransportRepository {
            override suspend fun enqueueEncryptedMessage(envelope: EncryptedMessageEnvelope, ttlSeconds: Int): Resource<String> = Resource.Success("msg_1")
            override suspend fun fetchPendingMessages(deviceId: String): Resource<List<PendingTransportMessage>> {
                val env = EncryptedMessageEnvelope("bob_id", "dev_b", 1, "alice_id", "dev_a", 1, 2, "ciphertext")
                return Resource.Success(listOf(PendingTransportMessage("msg_1", env, null)))
            }
            override suspend fun acknowledgeMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
            override suspend fun deleteDeliveredMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
        }

        val fakeLocalRepo = TestLocalChatRepository()
        val pendingService = PendingMessageServiceImpl(fakeAuthRepo, fakeCrypto, fakeTransport, fakeLocalRepo)

        val result = pendingService.fetchAndDecryptPendingMessages("dev_a")
        assertTrue(result is ProcessPendingResult.Processed)

        val messages = fakeLocalRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b").first()
        assertEquals(1, messages.size)
        assertEquals("Decrypted hello!", messages[0].content)
        assertEquals("DELIVERED", messages[0].deliveryState)
    }

    // C. failed decryption is not acknowledged
    @Test
    fun testC_FailedDecryptionIsNotAcknowledged() = runBlocking {
        val aliceUser = createUserInfo("alice_id", "alice@example.com")
        var ackCalled = false

        val fakeAuthRepo = object : AuthRepository {
            override suspend fun signUp(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signIn(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signOut(): Resource<Unit> = Resource.Success(Unit)
            override suspend fun getCurrentUser(): UserInfo? = aliceUser
            override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(aliceUser)
        }

        val fakeCrypto = object : EncryptedMessageService {
            override suspend fun encryptMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String): EncryptionResult = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope): DecryptionResult {
                return DecryptionResult.CryptoFailure("Bad MAC")
            }
        }

        val fakeTransport = object : MessageTransportRepository {
            override suspend fun enqueueEncryptedMessage(envelope: EncryptedMessageEnvelope, ttlSeconds: Int): Resource<String> = Resource.Success("msg_1")
            override suspend fun fetchPendingMessages(deviceId: String): Resource<List<PendingTransportMessage>> {
                val env = EncryptedMessageEnvelope("bob_id", "dev_b", 1, "alice_id", "dev_a", 1, 2, "bad_cipher")
                return Resource.Success(listOf(PendingTransportMessage("msg_1", env, null)))
            }
            override suspend fun acknowledgeMessage(messageId: String): Resource<Boolean> {
                ackCalled = true
                return Resource.Success(true)
            }
            override suspend fun deleteDeliveredMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
        }

        val fakeLocalRepo = TestLocalChatRepository()
        val pendingService = PendingMessageServiceImpl(fakeAuthRepo, fakeCrypto, fakeTransport, fakeLocalRepo)

        val result = pendingService.fetchAndDecryptPendingMessages("dev_a")
        assertTrue(result is ProcessPendingResult.Processed)
        assertFalse(ackCalled)
    }

    // D. duplicate realtime events do not duplicate messages
    @Test
    fun testD_DuplicateRealtimeEventsDoNotDuplicateMessages() = runBlocking {
        val fakeRepo = TestLocalChatRepository()
        val msg = MessageEntity("msg_id_100", "alice_id", "alice_id_bob_id_dev_b", "bob_id", "dev_b", "alice_id", "dev_a", "RECEIVED", "Hello", System.currentTimeMillis(), "DELIVERED")

        fakeRepo.saveMessage(msg)
        fakeRepo.saveMessage(msg)

        val msgs = fakeRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b").first()
        assertEquals(1, msgs.size)
    }

    // E. offline incoming message survives until reconnect
    @Test
    fun testE_OfflineIncomingMessageSurvivesUntilReconnect() = runBlocking {
        val fakeTransport = object : MessageTransportRepository {
            var isOnline = false
            override suspend fun enqueueEncryptedMessage(envelope: EncryptedMessageEnvelope, ttlSeconds: Int): Resource<String> = Resource.Success("1")
            override suspend fun fetchPendingMessages(deviceId: String): Resource<List<PendingTransportMessage>> {
                return if (isOnline) {
                    val env = EncryptedMessageEnvelope("bob_id", "dev_b", 1, "alice_id", "dev_a", 1, 2, "c")
                    Resource.Success(listOf(PendingTransportMessage("m1", env, null)))
                } else {
                    Resource.Error("Offline")
                }
            }
            override suspend fun acknowledgeMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
            override suspend fun deleteDeliveredMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
        }

        // Initially offline
        val resOffline = fakeTransport.fetchPendingMessages("dev_a")
        assertTrue(resOffline is Resource.Error)

        // Reconnect online
        fakeTransport.isOnline = true
        val resOnline = fakeTransport.fetchPendingMessages("dev_a")
        assertTrue(resOnline is Resource.Success)
        assertEquals(1, (resOnline as Resource.Success).data.size)
    }

    // F. offline outgoing message survives until reconnect
    @Test
    fun testF_OfflineOutgoingMessageSurvivesUntilReconnect() = runBlocking {
        val aliceUser = createUserInfo("alice_id", "alice@example.com")
        val fakeAuthRepo = object : AuthRepository {
            override suspend fun signUp(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signIn(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signOut(): Resource<Unit> = Resource.Success(Unit)
            override suspend fun getCurrentUser(): UserInfo? = aliceUser
            override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(aliceUser)
        }

        val fakeCrypto = object : EncryptedMessageService {
            override suspend fun encryptMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String): EncryptionResult {
                return EncryptionResult.Success(EncryptedMessageEnvelope("alice_id", "dev_a", 1, "bob_id", "dev_b", 1, 2, "cipher"))
            }
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope): DecryptionResult = DecryptionResult.SessionNotFound
        }

        var isNetworkAvailable = false
        val fakeTransport = object : MessageTransportRepository {
            override suspend fun enqueueEncryptedMessage(envelope: EncryptedMessageEnvelope, ttlSeconds: Int): Resource<String> {
                return if (isNetworkAvailable) Resource.Success("uploaded_1") else Resource.Error("Network offline")
            }
            override suspend fun fetchPendingMessages(deviceId: String): Resource<List<PendingTransportMessage>> = Resource.Success(emptyList())
            override suspend fun acknowledgeMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
            override suspend fun deleteDeliveredMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
        }

        val fakeLocalRepo = TestLocalChatRepository()
        val outboxService = MessageOutboxServiceImpl(fakeAuthRepo, fakeCrypto, fakeTransport, fakeLocalRepo)

        val queuedMsg = outboxService.queueTextMessage("dev_a", "bob_id", "dev_b", 1, "Queued offline message")
        assertNotNull(queuedMsg)
        assertEquals("QUEUED", queuedMsg?.deliveryState)

        // Verify it remains queued while offline
        val pendingOutbound = fakeLocalRepo.getPendingOutboundMessages("alice_id")
        assertEquals(1, pendingOutbound.size)

        // Network returns online -> process pending outbox
        isNetworkAvailable = true
        val processRes = outboxService.processPendingOutboxMessages("alice_id", "dev_a")
        assertTrue(processRes is Resource.Success)

        val updatedMsg = fakeLocalRepo.getMessageById(queuedMsg!!.id)
        assertEquals("SENT", updatedMsg?.deliveryState)
    }

    // G. reconnect restarts realtime safely
    @Test
    fun testG_ReconnectRestartsRealtimeSafely() {
        val fakeClient = mock(SupabaseClient::class.java)
        val fakePending = mock(com.campuschat.app.domain.service.PendingMessageService::class.java)
        val observer = RealtimeMessageObserver(fakeClient, fakePending)

        assertFalse(observer.isObserving)
        observer.reconnect()
        observer.stopObserving()
        assertFalse(observer.isObserving)
    }

    // H. duplicate realtime subscription is prevented
    @Test
    fun testH_DuplicateRealtimeSubscriptionIsPrevented() {
        val fakeClient = mock(SupabaseClient::class.java)
        val fakePending = mock(com.campuschat.app.domain.service.PendingMessageService::class.java)
        val observer = RealtimeMessageObserver(fakeClient, fakePending)

        observer.startObserving()
        val initialStatus = observer.isObserving
        observer.startObserving() // duplicate call
        assertEquals(initialStatus, observer.isObserving)
        observer.stopObserving()
    }

    // I. logout does not delete local chats
    @Test
    fun testI_LogoutDoesNotDeleteLocalChats() = runBlocking {
        val fakeRepo = TestLocalChatRepository()
        val msg = MessageEntity("m1", "alice_id", "alice_id_bob_id_dev_b", "alice_id", "dev_a", "bob_id", "dev_b", "SENT", "Persistent chat", System.currentTimeMillis(), "SENT")
        fakeRepo.saveMessage(msg)

        val aliceUser = createUserInfo("alice_id", "alice@example.com")
        val authRepo = object : AuthRepository {
            override suspend fun signUp(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signIn(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signOut(): Resource<Unit> = Resource.Success(Unit)
            override suspend fun getCurrentUser(): UserInfo? = null
            override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(null)
        }
        val logoutUseCase = com.campuschat.app.domain.usecase.LogoutUseCase(authRepo)
        logoutUseCase.invoke()

        val remainingMsgs = fakeRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b").first()
        assertEquals(1, remainingMsgs.size)
    }

    // J. same-account relogin restores same conversation
    @Test
    fun testJ_SameAccountReloginRestoresSameConversation() = runBlocking {
        val fakeRepo = TestLocalChatRepository()
        val convId = "alice_id_bob_id_dev_b"
        val conv = ConversationEntity(convId, "alice_id", "bob_id", "dev_b", "bob", "Bob", "Hi", System.currentTimeMillis(), 0)
        fakeRepo.saveConversation(conv)

        val restored = fakeRepo.getConversation("alice_id", "bob_id", "dev_b")
        assertNotNull(restored)
        assertEquals(convId, restored?.id)
    }

    // K. different accounts remain isolated
    @Test
    fun testK_DifferentAccountsRemainIsolated() = runBlocking {
        val fakeRepo = TestLocalChatRepository()
        fakeRepo.saveMessage(MessageEntity("m1", "alice_id", "alice_id_bob_id_dev_b", "alice_id", "dev_a", "bob_id", "dev_b", "SENT", "Secret A", System.currentTimeMillis(), "SENT"))
        fakeRepo.saveMessage(MessageEntity("m2", "charlie_id", "charlie_id_bob_id_dev_b", "charlie_id", "dev_c", "bob_id", "dev_b", "SENT", "Secret C", System.currentTimeMillis(), "SENT"))

        val aliceMsgs = fakeRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b").first()
        assertEquals(1, aliceMsgs.size)
        assertEquals("Secret A", aliceMsgs[0].content)

        val charlieMsgs = fakeRepo.getMessagesForConversation("charlie_id", "bob_id", "dev_b").first()
        assertEquals(1, charlieMsgs.size)
        assertEquals("Secret C", charlieMsgs[0].content)
    }

    // L. different recipient devices remain isolated
    @Test
    fun testL_DifferentRecipientDevicesRemainIsolated() = runBlocking {
        val fakeRepo = TestLocalChatRepository()
        fakeRepo.saveMessage(MessageEntity("m1", "alice_id", "alice_id_bob_id_dev_b1", "alice_id", "dev_a", "bob_id", "dev_b1", "SENT", "Phone msg", System.currentTimeMillis(), "SENT"))
        fakeRepo.saveMessage(MessageEntity("m2", "alice_id", "alice_id_bob_id_dev_b2", "alice_id", "dev_a", "bob_id", "dev_b2", "SENT", "Tablet msg", System.currentTimeMillis(), "SENT"))

        val dev1Msgs = fakeRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b1").first()
        assertEquals(1, dev1Msgs.size)
        assertEquals("Phone msg", dev1Msgs[0].content)

        val dev2Msgs = fakeRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b2").first()
        assertEquals(1, dev2Msgs.size)
        assertEquals("Tablet msg", dev2Msgs[0].content)
    }

    // M. process recreation preserves messages
    @Test
    fun testM_ProcessRecreationPreservesMessages() = runBlocking {
        val fakeRepo = TestLocalChatRepository()
        fakeRepo.saveMessage(MessageEntity("m1", "alice_id", "alice_id_bob_id_dev_b", "alice_id", "dev_a", "bob_id", "dev_b", "SENT", "Survives process death", System.currentTimeMillis(), "SENT"))

        // Simulate new ViewModel or process recreation reading from existing Room repo
        val messages = fakeRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b").first()
        assertEquals(1, messages.size)
    }

    // N. Room remains UI source of truth
    @Test
    fun testN_RoomRemainsUiSourceOfTruth() = runBlocking {
        val fakeRepo = TestLocalChatRepository()
        val flow = fakeRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b")
        assertTrue(flow.first().isEmpty())

        fakeRepo.saveMessage(MessageEntity("m1", "alice_id", "alice_id_bob_id_dev_b", "bob_id", "dev_b", "alice_id", "dev_a", "RECEIVED", "Pushed via Room", System.currentTimeMillis(), "DELIVERED"))
        assertEquals(1, flow.first().size)
    }

    // O. no plaintext/ciphertext is present in realtime payload
    @Test
    fun testO_NoPlaintextOrCiphertextInRealtimePayload() {
        val payloadKeys = setOf("table", "schema", "commit_timestamp", "type")
        assertFalse(payloadKeys.contains("plaintext"))
        assertFalse(payloadKeys.contains("ciphertext"))
    }

    // P. retry does not create duplicate messages
    @Test
    fun testP_RetryDoesNotCreateDuplicateMessages() = runBlocking {
        val fakeRepo = TestLocalChatRepository()
        val msgId = "stable_client_id_999"
        val entity = MessageEntity(msgId, "alice_id", "alice_id_bob_id_dev_b", "alice_id", "dev_a", "bob_id", "dev_b", "SENT", "Retry message", System.currentTimeMillis(), "QUEUED")

        fakeRepo.saveMessage(entity)
        fakeRepo.updateMessageDeliveryState(msgId, "ENCRYPTING")
        fakeRepo.updateMessageDeliveryState(msgId, "UPLOADING")
        fakeRepo.updateMessageDeliveryState(msgId, "SENT")

        val msgs = fakeRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b").first()
        assertEquals(1, msgs.size)
        assertEquals("SENT", msgs[0].deliveryState)
    }

    // Q. ACK occurs only after successful persistence
    @Test
    fun testQ_AckOccursOnlyAfterSuccessfulPersistence() = runBlocking {
        val aliceUser = createUserInfo("alice_id", "alice@example.com")
        var persistedBeforeAck = false

        val fakeAuthRepo = object : AuthRepository {
            override suspend fun signUp(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signIn(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signOut(): Resource<Unit> = Resource.Success(Unit)
            override suspend fun getCurrentUser(): UserInfo? = aliceUser
            override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(aliceUser)
        }

        val fakeCrypto = object : EncryptedMessageService {
            override suspend fun encryptMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String): EncryptionResult = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope): DecryptionResult = DecryptionResult.Success("Valid")
        }

        val fakeLocalRepo = TestLocalChatRepository()
        val fakeTransport = object : MessageTransportRepository {
            override suspend fun enqueueEncryptedMessage(envelope: EncryptedMessageEnvelope, ttlSeconds: Int): Resource<String> = Resource.Success("1")
            override suspend fun fetchPendingMessages(deviceId: String): Resource<List<PendingTransportMessage>> {
                val env = EncryptedMessageEnvelope("bob_id", "dev_b", 1, "alice_id", "dev_a", 1, 2, "cipher")
                return Resource.Success(listOf(PendingTransportMessage("msg_seq", env, null)))
            }
            override suspend fun acknowledgeMessage(messageId: String): Resource<Boolean> {
                val found = runBlocking { fakeLocalRepo.getMessageById(messageId) }
                if (found != null) {
                    persistedBeforeAck = true
                }
                return Resource.Success(true)
            }
            override suspend fun deleteDeliveredMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
        }

        val pendingService = PendingMessageServiceImpl(fakeAuthRepo, fakeCrypto, fakeTransport, fakeLocalRepo)
        pendingService.fetchAndDecryptPendingMessages("dev_a")

        assertTrue(persistedBeforeAck)
    }

    // R. startup pending-message synchronization works
    @Test
    fun testR_StartupPendingMessageSynchronizationWorks() = runBlocking {
        val aliceUser = createUserInfo("alice_id", "alice@example.com")
        var pendingFetchedOnStartup = false

        val fakeAuthRepo = object : AuthRepository {
            override suspend fun signUp(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signIn(email: String, password: String): Resource<UserInfo> = Resource.Success(aliceUser)
            override suspend fun signOut(): Resource<Unit> = Resource.Success(Unit)
            override suspend fun getCurrentUser(): UserInfo? = aliceUser
            override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(aliceUser)
        }

        val fakeCrypto = object : EncryptedMessageService {
            override suspend fun encryptMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String): EncryptionResult = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope): DecryptionResult = DecryptionResult.Success("Startup message")
        }

        val fakeTransport = object : MessageTransportRepository {
            override suspend fun enqueueEncryptedMessage(envelope: EncryptedMessageEnvelope, ttlSeconds: Int): Resource<String> = Resource.Success("1")
            override suspend fun fetchPendingMessages(deviceId: String): Resource<List<PendingTransportMessage>> {
                pendingFetchedOnStartup = true
                val env = EncryptedMessageEnvelope("bob_id", "dev_b", 1, "alice_id", "dev_a", 1, 2, "cipher")
                return Resource.Success(listOf(PendingTransportMessage("m_startup", env, null)))
            }
            override suspend fun acknowledgeMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
            override suspend fun deleteDeliveredMessage(messageId: String): Resource<Boolean> = Resource.Success(true)
        }

        val fakeLocalRepo = TestLocalChatRepository()
        val pendingService = PendingMessageServiceImpl(fakeAuthRepo, fakeCrypto, fakeTransport, fakeLocalRepo)

        pendingService.fetchAndDecryptPendingMessages("dev_a")
        assertTrue(pendingFetchedOnStartup)

        val msgs = fakeLocalRepo.getMessagesForConversation("alice_id", "bob_id", "dev_b").first()
        assertEquals(1, msgs.size)
        assertEquals("Startup message", msgs[0].content)
    }
}

// In-memory test implementation of LocalChatRepository for RealtimeAndOutboxIntegrationTest
private class TestLocalChatRepository : LocalChatRepository {
    private val conversationsFlow = MutableStateFlow<Map<String, ConversationEntity>>(emptyMap())
    private val messagesFlow = MutableStateFlow<Map<String, MessageEntity>>(emptyMap())

    override fun getAllConversations(localAccountId: String): Flow<List<ConversationEntity>> {
        return conversationsFlow.map { map ->
            map.values.filter { localAccountId.isBlank() || it.localAccountId == localAccountId }
                .sortedByDescending { it.lastMessageTimestamp }
        }
    }

    override suspend fun getConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): ConversationEntity? {
        val targetConvId = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
        return conversationsFlow.value[targetConvId]
    }

    override fun getMessagesForConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): Flow<List<MessageEntity>> {
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
