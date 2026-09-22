package com.campuschat.app.presentation.chat.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.X3DHSessionResult
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.service.MessageOutboxService
import com.campuschat.app.domain.service.PendingMessageService
import com.campuschat.app.domain.service.ProcessPendingResult
import com.campuschat.app.domain.service.X3DHSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MessageUiItem(
    val messageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val plaintext: String,
    val isFromSelf: Boolean,
    val deliveryState: String = "SENT", // "PENDING", "ENCRYPTING", "UPLOADING", "SENT", "DELIVERED", "FAILED"
    val timestamp: String = "Just now"
)

data class ConversationUiState(
    val recipientUserId: String = "",
    val recipientDeviceId: String = "",
    val recipientDisplayName: String = "Campus User",
    val messageInput: String = "",
    val messages: List<MessageUiItem> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null
)

class ConversationViewModel(
    private val recipientUserId: String,
    private val recipientDeviceId: String,
    private val messageOutboxService: MessageOutboxService,
    private val pendingMessageService: PendingMessageService,
    private val x3dhSessionService: X3DHSessionService? = null,
    private val localChatRepository: LocalChatRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ConversationUiState(
            recipientUserId = recipientUserId,
            recipientDeviceId = recipientDeviceId,
            recipientDisplayName = "User ${recipientUserId.take(8)}"
        )
    )
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        observeLocalMessages()
        fetchPending()
    }

    private fun observeLocalMessages() {
        val repo = localChatRepository ?: return
        val currentUserId = com.campuschat.app.core.session.SessionManager.getCurrentUserId() ?: ""
        viewModelScope.launch {
            repo.getMessagesForConversation(currentUserId, recipientUserId, recipientDeviceId)
                .catch { /* ignore */ }
                .collect { entityList ->
                    val uiItems = entityList.map { entity ->
                        val isSelf = entity.senderUserId == currentUserId || entity.direction == "SENT"
                        val timeFormatted = try {
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entity.timestamp))
                        } catch (e: Exception) {
                            "Just now"
                        }
                        MessageUiItem(
                            messageId = entity.id,
                            senderUserId = entity.senderUserId,
                            senderDeviceId = entity.senderDeviceId,
                            plaintext = entity.content,
                            isFromSelf = isSelf,
                            deliveryState = entity.deliveryState,
                            timestamp = timeFormatted
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        messages = uiItems
                    )
                }
        }
    }

    fun onMessageInputChanged(newInput: String) {
        _uiState.value = _uiState.value.copy(messageInput = newInput, errorMessage = null)
    }

    fun fetchPending() {
        val localDeviceId = DeviceIdProvider.getDeviceId()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = pendingMessageService.fetchAndDecryptPendingMessages(localDeviceId)) {
                is ProcessPendingResult.Processed -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                is ProcessPendingResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun sendMessage() {
        val input = _uiState.value.messageInput.trim()
        if (input.isBlank() || _uiState.value.isSending) return

        val localDeviceId = DeviceIdProvider.getDeviceId()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, errorMessage = null)

            // Step 1: Ensure X3DH session is established if service is provided
            var targetRegistrationId = 1
            if (x3dhSessionService != null) {
                when (val sessionRes = x3dhSessionService.establishOutboundSession(recipientDeviceId)) {
                    is X3DHSessionResult.SessionEstablished -> {
                        targetRegistrationId = sessionRes.address.deviceId
                    }
                    is X3DHSessionResult.CryptoFailure -> {
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            errorMessage = sessionRes.reason
                        )
                        return@launch
                    }
                    is X3DHSessionResult.NetworkFailure -> {
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            errorMessage = sessionRes.reason
                        )
                        return@launch
                    }
                    is X3DHSessionResult.StorageFailure -> {
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            errorMessage = sessionRes.reason
                        )
                        return@launch
                    }
                    is X3DHSessionResult.InvalidPreKeyBundle -> {
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            errorMessage = sessionRes.reason
                        )
                        return@launch
                    }
                    is X3DHSessionResult.IdentityChanged -> {
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            errorMessage = "Identity changed for recipient device."
                        )
                        return@launch
                    }
                    is X3DHSessionResult.RecipientDeviceUnavailable -> {
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            errorMessage = sessionRes.reason
                        )
                        return@launch
                    }
                    is X3DHSessionResult.AuthenticationRequired -> {
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            errorMessage = "Authentication required."
                        )
                        return@launch
                    }
                }
            }

            // Step 2: Encrypt & send message envelope
            val result = messageOutboxService.sendEncryptedTextMessage(
                senderDeviceId = localDeviceId,
                recipientUserId = recipientUserId,
                recipientDeviceId = recipientDeviceId,
                recipientRegistrationId = targetRegistrationId,
                plaintext = input
            )

            when (result) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        messageInput = ""
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        errorMessage = result.message
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isSending = false)
                }
            }
        }
    }
}
