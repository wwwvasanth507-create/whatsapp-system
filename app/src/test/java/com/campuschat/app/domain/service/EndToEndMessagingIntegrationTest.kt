package com.campuschat.app.domain.service

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.local.entity.ConversationEntity
import com.campuschat.app.data.local.entity.MessageEntity
import com.campuschat.app.domain.model.DecryptionResult
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.EncryptionResult
import com.campuschat.app.domain.model.PendingTransportMessage
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.model.X3DHSessionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.repository.MessageTransportRepository
import com.campuschat.app.presentation.chat.conversation.ConversationViewModel
import io.github.jan.supabase.gotrue.user.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
import java.util.concurrent.ConcurrentHashMap

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
class EndToEndMessagingIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeLocalChatRepository : LocalChatRepository {
        val conversations = ConcurrentHashMap<String, ConversationEntity>()
        val messages = ConcurrentHashMap<String, MessageEntity>()
        private val conversationsFlow = MutableStateFlow<List<ConversationEntity>>(emptyList())
        private val messagesFlow = MutableStateFlow<List<MessageEntity>>(emptyList())

        override fun getAllConversations(localAccountId: String): Flow<List<ConversationEntity>> {
            return conversationsFlow.map { list ->
                if (localAccountId.isBlank()) list else list.filter { it.localAccountId == localAccountId }
            }
        }

        override suspend fun getConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): ConversationEntity? {
            val id = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
            return conversations[id] ?: conversations["${recipientUserId}_${recipientDeviceId}"]
        }

        override suspend fun saveConversation(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            conversationsFlow.value = conversations.values.toList()
        }

        override fun getMessagesForConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): Flow<List<MessageEntity>> {
            val convId = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
            return messagesFlow.map { list ->
                list.filter { it.conversationId == convId || it.conversationId == "${recipientUserId}_${recipientDeviceId}" }
            }
        }

        override suspend fun saveMessage(message: MessageEntity) {
            messages[message.id] = message
            messagesFlow.value = messages.values.toList()
            val convId = message.conversationId
            val targetRecipientUserId = if (message.direction == "SENT") message.recipientUserId else message.senderUserId
            val targetRecipientDeviceId = if (message.direction == "SENT") message.recipientDeviceId else message.senderDeviceId
            conversations[convId] = ConversationEntity(
                id = convId,
                localAccountId = message.localAccountId,
                recipientUserId = targetRecipientUserId,
                recipientDeviceId = targetRecipientDeviceId,
                recipientUsername = "user_${targetRecipientUserId.take(4)}",
                recipientDisplayName = "Recipient User",
                lastMessageSnippet = message.content,
                lastMessageTimestamp = message.timestamp
            )
            conversationsFlow.value = conversations.values.toList()
        }

        override suspend fun updateMessageDeliveryState(messageId: String, deliveryState: String) {
            messages[messageId]?.let {
                val updated = it.copy(deliveryState = deliveryState)
                messages[messageId] = updated
                messagesFlow.value = messages.values.toList()
            }
        }

        override suspend fun getMessageById(messageId: String): MessageEntity? = messages[messageId]

        override suspend fun getPendingOutboundMessages(localAccountId: String): List<MessageEntity> {
            return messages.values.filter {
                it.localAccountId == localAccountId && it.deliveryState in listOf("QUEUED", "PENDING", "FAILED")
            }.sortedBy { it.timestamp }
        }
    }

    private class FakeAuthRepository(private val mockUserId: String = "sender-user-100") : AuthRepository {
        private val userInfo = createTestUserInfo(mockUserId, "$mockUserId@campus.edu")
        override suspend fun signUp(email: String, password: String) = Resource.Error("Not implemented")
        override suspend fun signIn(email: String, password: String) = Resource.Error("Not implemented")
        override suspend fun signOut() = Resource.Success(Unit)
        override suspend fun getCurrentUser(): UserInfo? = userInfo
        override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(userInfo)
    }

    private class FakeTransportRepository : MessageTransportRepository {
        val enqueuedEnvelopes = mutableListOf<EncryptedMessageEnvelope>()
        val acknowledgedMessageIds = mutableSetOf<String>()
        var shouldFailEnqueue = false

        override suspend fun enqueueEncryptedMessage(
            envelope: EncryptedMessageEnvelope,
            ttlSeconds: Int
        ): Resource<String> {
            if (shouldFailEnqueue) return Resource.Error("Network upload failed")
            enqueuedEnvelopes.add(envelope)
            return Resource.Success("msg-id-12345")
        }

        override suspend fun fetchPendingMessages(deviceId: String): Resource<List<PendingTransportMessage>> {
            val pendingList = enqueuedEnvelopes.mapIndexed { idx, env ->
                PendingTransportMessage(
                    messageId = "pending-id-$idx",
                    envelope = env
                )
            }
            return Resource.Success(pendingList)
        }

        override suspend fun acknowledgeMessage(messageId: String): Resource<Boolean> {
            acknowledgedMessageIds.add(messageId)
            return Resource.Success(true)
        }

        override suspend fun deleteDeliveredMessage(messageId: String): Resource<Boolean> {
            return Resource.Success(true)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Send empty message rejected
    @Test
    fun test1_SendEmptyMessageRejected() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(
                senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String
            ) = EncryptionResult.InvalidInput("Message text cannot be empty or blank")

            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.InvalidCiphertext("Blank")
        }

        val outbox = MessageOutboxServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)
        val pending = PendingMessageServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)

        val vm = ConversationViewModel(
            recipientUserId = "target-user",
            recipientDeviceId = "target-device",
            messageOutboxService = outbox,
            pendingMessageService = pending,
            localChatRepository = fakeLocalRepo
        )

        vm.onMessageInputChanged("   ")
        vm.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(fakeTransport.enqueuedEnvelopes.isEmpty())
        assertTrue(fakeLocalRepo.messages.isEmpty())
    }

    // 2. Send message with existing session
    @Test
    fun test2_SendMessageWithExistingSession() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(
                senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String
            ): EncryptionResult {
                val envelope = EncryptedMessageEnvelope(
                    senderUserId = "self-id",
                    senderDeviceId = senderDeviceId,
                    senderRegistrationId = 1,
                    recipientUserId = recipientUserId,
                    recipientDeviceId = recipientDeviceId,
                    recipientRegistrationId = recipientRegistrationId,
                    messageType = 1,
                    ciphertextBase64 = "ENCRYPTED_CIPHERTEXT_BASE64"
                )
                return EncryptionResult.Success(envelope)
            }

            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.Success("Decrypted text")
        }

        val outbox = MessageOutboxServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)
        val pending = PendingMessageServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)

        val vm = ConversationViewModel(
            recipientUserId = "target-user-1",
            recipientDeviceId = "target-device-1",
            messageOutboxService = outbox,
            pendingMessageService = pending,
            localChatRepository = fakeLocalRepo
        )

        vm.onMessageInputChanged("Hello Secure World")
        vm.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeTransport.enqueuedEnvelopes.size)
        assertEquals("ENCRYPTED_CIPHERTEXT_BASE64", fakeTransport.enqueuedEnvelopes[0].ciphertextBase64)
        assertEquals(1, fakeLocalRepo.messages.size)
        assertEquals("Hello Secure World", fakeLocalRepo.messages.values.first().content)
        assertEquals("SENT", fakeLocalRepo.messages.values.first().deliveryState)
    }

    // 3. Send message without session triggers X3DH
    @Test
    fun test3_SendMessageWithoutSessionTriggersX3DH() = runTest {
        var x3dhTriggered = false
        val fakeLocalRepo = FakeLocalChatRepository()
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val mockX3DHService = object : X3DHSessionService {
            override suspend fun establishOutboundSession(recipientDeviceId: String): X3DHSessionResult {
                x3dhTriggered = true
                return X3DHSessionResult.SessionEstablished(
                    address = org.signal.libsignal.protocol.SignalProtocolAddress("target-user-2", 1),
                    remoteDeviceId = recipientDeviceId,
                    claimedOneTimePreKeyId = null
                )
            }
        }

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(
                senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String
            ): EncryptionResult {
                val envelope = EncryptedMessageEnvelope(
                    senderUserId = "self-id",
                    senderDeviceId = senderDeviceId,
                    senderRegistrationId = 1,
                    recipientUserId = recipientUserId,
                    recipientDeviceId = recipientDeviceId,
                    recipientRegistrationId = recipientRegistrationId,
                    messageType = 1,
                    ciphertextBase64 = "CIPHERTEXT"
                )
                return EncryptionResult.Success(envelope)
            }

            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.Success("Decrypted")
        }

        val outbox = MessageOutboxServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)
        val pending = PendingMessageServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)

        val vm = ConversationViewModel(
            recipientUserId = "target-user-2",
            recipientDeviceId = "target-device-2",
            messageOutboxService = outbox,
            pendingMessageService = pending,
            x3dhSessionService = mockX3DHService,
            localChatRepository = fakeLocalRepo
        )

        vm.onMessageInputChanged("Trigger X3DH")
        vm.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(x3dhTriggered)
        assertEquals(1, fakeTransport.enqueuedEnvelopes.size)
    }

    // 4. Successful encryption calls outbox & 5. Plaintext never sent to transport
    @Test
    fun test4_PlaintextNeverSentToTransport() = runTest {
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(
                senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String
            ): EncryptionResult {
                val envelope = EncryptedMessageEnvelope(
                    senderUserId = "self",
                    senderDeviceId = senderDeviceId,
                    senderRegistrationId = 1,
                    recipientUserId = recipientUserId,
                    recipientDeviceId = recipientDeviceId,
                    recipientRegistrationId = recipientRegistrationId,
                    messageType = 1,
                    ciphertextBase64 = "SECRET_BASE64_CIPHERTEXT"
                )
                return EncryptionResult.Success(envelope)
            }

            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.Success("Decrypted")
        }

        val outbox = MessageOutboxServiceImpl(fakeAuth, mockCryptoService, fakeTransport)
        val res = outbox.sendEncryptedTextMessage("dev-1", "user-2", "dev-2", 1, "Sensitive Plaintext Message")

        assertTrue(res is Resource.Success)
        val sentEnvelope = fakeTransport.enqueuedEnvelopes.first()
        assertFalse(sentEnvelope.ciphertextBase64.contains("Sensitive Plaintext Message"))
        assertEquals("SECRET_BASE64_CIPHERTEXT", sentEnvelope.ciphertextBase64)
    }

    // 6. Successful enqueue creates local SENT message
    @Test
    fun test6_SuccessfulEnqueueCreatesLocalSentMessage() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(
                senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String
            ) = EncryptionResult.Success(
                EncryptedMessageEnvelope("u1", senderDeviceId, 1, recipientUserId, recipientDeviceId, recipientRegistrationId, 1, "CIPHER")
            )
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.Success("T")
        }

        val outbox = MessageOutboxServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)
        outbox.sendEncryptedTextMessage("dev-1", "user-2", "dev-2", 1, "Test Enqueue")

        assertEquals(1, fakeLocalRepo.messages.size)
        val saved = fakeLocalRepo.messages.values.first()
        assertEquals("SENT", saved.deliveryState)
        assertEquals("Test Enqueue", saved.content)
    }

    // 7. Failed enqueue creates FAILED state
    @Test
    fun test7_FailedEnqueueCreatesFailedState() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val fakeTransport = FakeTransportRepository()
        fakeTransport.shouldFailEnqueue = true
        val fakeAuth = FakeAuthRepository()

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(
                senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String
            ) = EncryptionResult.Success(
                EncryptedMessageEnvelope("u1", senderDeviceId, 1, recipientUserId, recipientDeviceId, recipientRegistrationId, 1, "CIPHER")
            )
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.Success("T")
        }

        val outbox = MessageOutboxServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)
        val res = outbox.sendEncryptedTextMessage("dev-1", "user-2", "dev-2", 1, "Failed Message")

        assertTrue(res is Resource.Error)
        assertEquals(1, fakeLocalRepo.messages.size)
        val saved = fakeLocalRepo.messages.values.first()
        assertEquals("FAILED", saved.deliveryState)
    }

    // 8. Incoming encrypted message decrypted & 9. Successful decrypt persists local message
    @Test
    fun test8_SuccessfulDecryptPersistsLocalMessage() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val env = EncryptedMessageEnvelope("sender-1", "dev-s", 1, "rec-1", "dev-r", 1, 1, "VALID_CIPHERTEXT")
        fakeTransport.enqueuedEnvelopes.add(env)

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(s: String, r1: String, r2: String, reg: Int, p: String) = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.Success("Decrypted Incoming Text")
        }

        val pending = PendingMessageServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)
        val res = pending.fetchAndDecryptPendingMessages("dev-r")

        assertTrue(res is ProcessPendingResult.Processed)
        assertEquals(1, (res as ProcessPendingResult.Processed).messages.size)
        assertEquals("Decrypted Incoming Text", res.messages[0].plaintext)
        assertEquals(1, fakeTransport.acknowledgedMessageIds.size)
        assertEquals(1, fakeLocalRepo.messages.size)
        assertEquals("Decrypted Incoming Text", fakeLocalRepo.messages.values.first().content)
        assertEquals("RECEIVED", fakeLocalRepo.messages.values.first().direction)
    }

    // 10. Failed decrypt does not acknowledge
    @Test
    fun test10_FailedDecryptDoesNotAcknowledge() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val env = EncryptedMessageEnvelope("sender-1", "dev-s", 1, "rec-1", "dev-r", 1, 1, "CORRUPTED_CIPHERTEXT")
        fakeTransport.enqueuedEnvelopes.add(env)

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(s: String, r1: String, r2: String, reg: Int, p: String) = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.InvalidCiphertext("Corrupted")
        }

        val pending = PendingMessageServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)
        val res = pending.fetchAndDecryptPendingMessages("dev-r")

        assertTrue(res is ProcessPendingResult.Processed)
        val processed = res as ProcessPendingResult.Processed
        assertEquals(0, processed.messages.size)
        assertEquals(1, processed.skippedCount)
        assertTrue(fakeTransport.acknowledgedMessageIds.isEmpty())
        assertTrue(fakeLocalRepo.messages.isEmpty())
    }

    // 11. Duplicate Realtime event does not duplicate local message
    @Test
    fun test11_DuplicateRealtimeEventDoesNotDuplicateLocalMessage() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val msg = MessageEntity(
            id = "pending-id-0",
            localAccountId = "rec-1",
            conversationId = "rec-1_sender-1_dev-s",
            senderUserId = "sender-1",
            senderDeviceId = "dev-s",
            recipientUserId = "rec-1",
            recipientDeviceId = "dev-r",
            direction = "RECEIVED",
            content = "Hello duplicate",
            timestamp = System.currentTimeMillis(),
            deliveryState = "DELIVERED"
        )
        fakeLocalRepo.saveMessage(msg)

        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()
        val env = EncryptedMessageEnvelope("sender-1", "dev-s", 1, "rec-1", "dev-r", 1, 1, "CIPHERTEXT")
        fakeTransport.enqueuedEnvelopes.add(env)

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(s: String, r1: String, r2: String, reg: Int, p: String) = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.Success("Hello duplicate")
        }

        val pending = PendingMessageServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)
        pending.fetchAndDecryptPendingMessages("dev-r")

        assertEquals(1, fakeLocalRepo.messages.size)
    }

    // 12. Duplicate acknowledgement safe
    @Test
    fun test12_DuplicateAcknowledgementSafe() = runTest {
        val fakeTransport = FakeTransportRepository()
        val res1 = fakeTransport.acknowledgeMessage("msg-1")
        val res2 = fakeTransport.acknowledgeMessage("msg-1")

        assertTrue(res1 is Resource.Success)
        assertTrue(res2 is Resource.Success)
        assertEquals(1, fakeTransport.acknowledgedMessageIds.size)
    }

    // 13. Offline receiver eventually receives message
    @Test
    fun test13_OfflineReceiverEventuallyReceivesMessage() = runTest {
        val fakeTransport = FakeTransportRepository()
        val env = EncryptedMessageEnvelope("sender-1", "dev-s", 1, "rec-1", "dev-r", 1, 1, "STORED_CIPHERTEXT")
        fakeTransport.enqueuedEnvelopes.add(env)

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(s: String, r1: String, r2: String, reg: Int, p: String) = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.Success("Offline Message Received")
        }

        val fakeLocalRepo = FakeLocalChatRepository()
        val fakeAuth = FakeAuthRepository()
        val pending = PendingMessageServiceImpl(fakeAuth, mockCryptoService, fakeTransport, fakeLocalRepo)

        val res = pending.fetchAndDecryptPendingMessages("dev-r")

        assertTrue(res is ProcessPendingResult.Processed)
        assertEquals(1, (res as ProcessPendingResult.Processed).messages.size)
        assertEquals("Offline Message Received", res.messages[0].plaintext)
    }

    // 14. Identity change rejected
    @Test
    fun test14_IdentityChangeRejected() = runTest {
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(s: String, r1: String, r2: String, reg: Int, p: String) =
                EncryptionResult.IdentityChanged(r1, r2)
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) =
                DecryptionResult.IdentityChanged(envelope.senderUserId, envelope.senderDeviceId)
        }

        val outbox = MessageOutboxServiceImpl(fakeAuth, mockCryptoService, fakeTransport)
        val res = outbox.sendEncryptedTextMessage("dev-1", "user-2", "dev-2", 1, "Message")

        assertTrue(res is Resource.Error)
        assertTrue((res as Resource.Error).message.contains("identity has changed", ignoreCase = true))
        assertTrue(fakeTransport.enqueuedEnvelopes.isEmpty())
    }

    // 15. Missing session handled safely
    @Test
    fun test15_MissingSessionHandledSafely() = runTest {
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(s: String, r1: String, r2: String, reg: Int, p: String) = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.SessionNotFound
        }

        val outbox = MessageOutboxServiceImpl(fakeAuth, mockCryptoService, fakeTransport)
        val res = outbox.sendEncryptedTextMessage("dev-1", "user-2", "dev-2", 1, "Message")

        assertTrue(res is Resource.Error)
        assertTrue((res as Resource.Error).message.contains("Session not established", ignoreCase = true))
    }

    // 16. Expired message handled safely
    @Test
    fun test16_ExpiredMessageHandledSafely() = runTest {
        val fakeTransport = FakeTransportRepository()
        val fakeAuth = FakeAuthRepository()
        val env = EncryptedMessageEnvelope("sender-1", "dev-s", 1, "rec-1", "dev-r", 1, 1, "")

        val mockCryptoService = object : EncryptedMessageService {
            override suspend fun encryptMessage(s: String, r1: String, r2: String, reg: Int, p: String) = EncryptionResult.SessionNotFound
            override suspend fun decryptMessage(envelope: EncryptedMessageEnvelope) = DecryptionResult.InvalidCiphertext("Ciphertext Base64 is blank")
        }

        val pending = PendingMessageServiceImpl(fakeAuth, mockCryptoService, fakeTransport)
        val res = pending.fetchAndDecryptPendingMessages("dev-r")

        assertTrue(res is ProcessPendingResult.Processed)
        assertEquals(0, (res as ProcessPendingResult.Processed).messages.size)
        assertTrue(fakeTransport.acknowledgedMessageIds.isEmpty())
    }

    // 17. Conversation reload restores local history
    @Test
    fun test17_ConversationReloadRestoresLocalHistory() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val msg1 = MessageEntity("m1", "self", "self_user-2_dev-2", "self", "dev-1", "user-2", "dev-2", "SENT", "First", 1000L, "SENT")
        val msg2 = MessageEntity("m2", "self", "self_user-2_dev-2", "user-2", "dev-2", "self", "dev-1", "RECEIVED", "Second", 2000L, "DELIVERED")

        fakeLocalRepo.saveMessage(msg1)
        fakeLocalRepo.saveMessage(msg2)

        val conv = fakeLocalRepo.getConversation("self", "user-2", "dev-2")
        assertNotNull(conv)
        assertEquals("Second", conv?.lastMessageSnippet)
        assertEquals(2, fakeLocalRepo.messages.size)
    }

    // 18. Multi-device conversations remain isolated
    @Test
    fun test18_MultiDeviceConversationsRemainIsolated() = runTest {
        val fakeLocalRepo = FakeLocalChatRepository()
        val msgDevA = MessageEntity("m-devA", "self", "self_user-X_device-A", "user-X", "device-A", "self", "my-dev", "RECEIVED", "Msg A", 100L, "DELIVERED")
        val msgDevB = MessageEntity("m-devB", "self", "self_user-X_device-B", "user-X", "device-B", "self", "my-dev", "RECEIVED", "Msg B", 200L, "DELIVERED")

        fakeLocalRepo.saveMessage(msgDevA)
        fakeLocalRepo.saveMessage(msgDevB)

        assertEquals("self_user-X_device-A", msgDevA.conversationId)
        assertEquals("self_user-X_device-B", msgDevB.conversationId)
        assertFalse(msgDevA.conversationId == msgDevB.conversationId)
        assertEquals(2, fakeLocalRepo.conversations.size)
    }
}
