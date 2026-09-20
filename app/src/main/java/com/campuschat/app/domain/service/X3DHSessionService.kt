package com.campuschat.app.domain.service

import com.campuschat.app.core.crypto.CampusChatSessionStore
import com.campuschat.app.core.crypto.CampusChatSignalProtocolStore
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.RemotePreKeyBundle
import com.campuschat.app.domain.model.X3DHSessionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.PreKeySyncRepository
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.SessionBuilder

interface X3DHSessionService {
    suspend fun establishOutboundSession(recipientDeviceId: String): X3DHSessionResult
}

class X3DHSessionServiceImpl(
    private val authRepository: AuthRepository,
    private val preKeySyncRepository: PreKeySyncRepository,
    private val protocolStore: CampusChatSignalProtocolStore,
    private val sessionStore: CampusChatSessionStore
) : X3DHSessionService {

    override suspend fun establishOutboundSession(recipientDeviceId: String): X3DHSessionResult {
        // 1. Check authenticated user session
        val currentUser = authRepository.getCurrentUser()
            ?: return X3DHSessionResult.AuthenticationRequired

        // 2. Claim PreKey Bundle from server
        val bundleResult = preKeySyncRepository.claimPreKeyBundle(recipientDeviceId)
        val bundle = when (bundleResult) {
            is Resource.Success -> bundleResult.data
            is Resource.Error -> {
                val errorMsg = bundleResult.message
                if (errorMsg.contains("inactive", ignoreCase = true) || errorMsg.contains("unavailable", ignoreCase = true)) {
                    return X3DHSessionResult.RecipientDeviceUnavailable(errorMsg)
                } else {
                    return X3DHSessionResult.NetworkFailure(errorMsg)
                }
            }
            is Resource.Loading, is Resource.Idle -> return X3DHSessionResult.NetworkFailure("Unexpected loading state")
        }

        // 3. Process and validate PreKey Bundle
        return processAndEstablishSession(bundle)
    }

    fun processAndEstablishSession(bundle: RemotePreKeyBundle): X3DHSessionResult {
        try {
            // Validate presence of required public components
            if (bundle.identityPublicKeyBase64.isBlank()) {
                return X3DHSessionResult.InvalidPreKeyBundle("Missing recipient identity public key")
            }
            if (bundle.signedPreKeyBase64.isBlank()) {
                return X3DHSessionResult.InvalidPreKeyBundle("Missing recipient signed prekey public key")
            }
            if (bundle.signedPreKeySignatureBase64.isBlank()) {
                return X3DHSessionResult.InvalidPreKeyBundle("Missing recipient signed prekey signature")
            }

            // Decode Identity Key
            val identityKeyBytes = decodeBase64(bundle.identityPublicKeyBase64)
            val identityEcKey = try {
                ECPublicKey(identityKeyBytes, 0, identityKeyBytes.size)
            } catch (e: Exception) {
                return X3DHSessionResult.InvalidPreKeyBundle("Invalid identity public key encoding")
            }
            val identityKey = IdentityKey(identityEcKey)

            // Decode Signed PreKey
            val signedPreKeyBytes = decodeBase64(bundle.signedPreKeyBase64)
            val signedPreKey: ECPublicKey = try {
                ECPublicKey(signedPreKeyBytes, 0, signedPreKeyBytes.size)
            } catch (e: Exception) {
                return X3DHSessionResult.InvalidPreKeyBundle("Invalid signed prekey public key encoding")
            }

            // Decode Signed PreKey Signature
            val signatureBytes = try {
                decodeBase64(bundle.signedPreKeySignatureBase64)
            } catch (e: Exception) {
                return X3DHSessionResult.InvalidPreKeyBundle("Invalid signed prekey signature encoding")
            }

            // 4. Verify Signed PreKey Signature against Recipient Identity Key
            val signatureValid = try {
                identityEcKey.verifySignature(signedPreKey.serialize(), signatureBytes)
            } catch (e: Exception) {
                false
            }

            if (!signatureValid) {
                // Abort! Do not establish session. Do not silently accept.
                return X3DHSessionResult.InvalidPreKeyBundle("Signed prekey signature verification failed")
            }

            // 5. Decode optional One-Time PreKey (OPK)
            var oneTimePreKey: ECPublicKey? = null
            if (!bundle.oneTimePreKeyBase64.isNullOrBlank() && bundle.oneTimePreKeyId != null) {
                try {
                    val opkBytes = decodeBase64(bundle.oneTimePreKeyBase64)
                    oneTimePreKey = ECPublicKey(opkBytes, 0, opkBytes.size)
                } catch (e: Exception) {
                    return X3DHSessionResult.InvalidPreKeyBundle("Invalid one-time prekey encoding")
                }
            }

            // 6. Construct Target SignalProtocolAddress
            val remoteAddress = SignalProtocolAddress(bundle.userId, bundle.registrationId)

            // 7. Identity / Trust Validation
            val isTrusted = protocolStore.isTrustedIdentity(
                remoteAddress,
                identityKey,
                IdentityKeyStore.Direction.SENDING
            )

            if (!isTrusted) {
                // Return explicit IdentityChanged error and stop
                return X3DHSessionResult.IdentityChanged(bundle.userId, bundle.deviceId)
            }

            // 8. Decode Kyber PreKey if available, or generate fallback for PreKeyBundle constructor
            val (kyberId, kyberKey, kyberSig) = if (!bundle.kyberPreKeyBase64.isNullOrBlank() && !bundle.kyberPreKeySignatureBase64.isNullOrBlank()) {
                val kKeyBytes = decodeBase64(bundle.kyberPreKeyBase64)
                val kSigBytes = decodeBase64(bundle.kyberPreKeySignatureBase64)
                val kKey = org.signal.libsignal.protocol.kem.KEMPublicKey(kKeyBytes)
                Triple(bundle.kyberPreKeyId ?: 1, kKey, kSigBytes)
            } else {
                val kemKeyPair = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.values()[0])
                Triple(0, kemKeyPair.publicKey, ByteArray(64))
            }

            val (opkId, opkKey) = if (oneTimePreKey != null && bundle.oneTimePreKeyId != null) {
                Pair(bundle.oneTimePreKeyId, oneTimePreKey)
            } else {
                Pair(-1, null)
            }

            val preKeyBundle = PreKeyBundle(
                bundle.registrationId,
                bundle.registrationId,
                opkId,
                opkKey,
                bundle.signedPreKeyId,
                signedPreKey,
                signatureBytes,
                identityKey,
                kyberId,
                kyberKey,
                kyberSig
            )

            // 9. Process X3DH Session Establishment via official libsignal SessionBuilder
            val sessionBuilder = SessionBuilder(protocolStore, remoteAddress)
            sessionBuilder.process(preKeyBundle)

            // 10. Persist trusted identity and remote device binding
            val identityChange = protocolStore.saveIdentity(remoteAddress, identityKey)
            if (identityChange.name.contains("REPLACED")) {
                return X3DHSessionResult.IdentityChanged(bundle.userId, bundle.deviceId)
            }
            sessionStore.bindRemoteDevice(remoteAddress, bundle.deviceId, identityKey)

            return X3DHSessionResult.SessionEstablished(
                address = remoteAddress,
                remoteDeviceId = bundle.deviceId,
                claimedOneTimePreKeyId = bundle.oneTimePreKeyId
            )

        } catch (e: org.signal.libsignal.protocol.UntrustedIdentityException) {
            return X3DHSessionResult.IdentityChanged(bundle.userId, bundle.deviceId)
        } catch (e: org.signal.libsignal.protocol.InvalidKeyException) {
            return X3DHSessionResult.CryptoFailure("Cryptographic error during session establishment: ${sanitizeErrorMessage(e)}")
        } catch (e: Exception) {
            return X3DHSessionResult.CryptoFailure("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun decodeBase64(base64Str: String): ByteArray {
        val trimmed = base64Str.trim()
        if (trimmed.isEmpty()) return ByteArray(0)
        return try {
            java.util.Base64.getDecoder().decode(trimmed)
        } catch (e1: Throwable) {
            try {
                java.util.Base64.getUrlDecoder().decode(trimmed)
            } catch (e2: Throwable) {
                android.util.Base64.decode(trimmed, android.util.Base64.NO_WRAP)
            }
        }
    }

    private fun sanitizeErrorMessage(e: Exception): String {
        val raw = e.message ?: "Session establishment failure"
        return raw.substringBefore("\nURL:").substringBefore("\nHeaders:").trim()
    }
}
