package com.campuschat.app.data.repository

import com.campuschat.app.core.crypto.IdentityKeyManager
import com.campuschat.app.core.crypto.PreKeyManager
import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.dto.ClaimedPreKeyBundleDto
import com.campuschat.app.data.dto.DeviceIdentityKeyDto
import com.campuschat.app.data.dto.DeviceKyberPreKeyDto
import com.campuschat.app.data.dto.DeviceOneTimePreKeyDto
import com.campuschat.app.data.dto.DeviceSignedPreKeyDto
import com.campuschat.app.domain.model.RemotePreKeyBundle
import com.campuschat.app.domain.repository.PreKeySyncRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc

class PreKeySyncRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val identityKeyManager: IdentityKeyManager,
    private val preKeyManager: PreKeyManager
) : PreKeySyncRepository {

    override suspend fun publishLocalPublicKeys(userId: String, deviceId: String, registrationId: Int): Resource<Unit> {
        return try {
            preKeyManager.initializePreKeys(deviceId)

            val pubIdentityKeyBase64 = identityKeyManager.getPublicIdentityKeyBase64()
                ?: return Resource.Error("Identity public key not available locally")

            // 1. Upsert Identity Public Key
            val identityDto = DeviceIdentityKeyDto(
                deviceId = deviceId,
                userId = userId,
                identityPublicKey = pubIdentityKeyBase64
            )
            supabaseClient.postgrest["device_identity_keys"].upsert(identityDto, onConflict = "device_id")

            // 2. Upsert Current Signed PreKey
            val localSpk = preKeyManager.getCurrentSignedPreKey(deviceId)
                ?: return Resource.Error("Signed prekey not available locally")

            val spkPublicKeyBase64 = encodeBase64(localSpk.keyPair.publicKey.serialize())
            val spkSignatureBase64 = encodeBase64(localSpk.signature)

            val spkDto = DeviceSignedPreKeyDto(
                deviceId = deviceId,
                userId = userId,
                keyId = localSpk.id,
                publicKey = spkPublicKeyBase64,
                signature = spkSignatureBase64,
                isActive = true
            )
            supabaseClient.postgrest["device_signed_prekeys"].upsert(spkDto, onConflict = "device_id,key_id")

            // 3. Upsert Current Kyber PreKey
            val localKyber = preKeyManager.getCurrentKyberPreKey(deviceId)
            if (localKyber != null) {
                val kyberPublicKeyBase64 = encodeBase64(localKyber.keyPair.publicKey.serialize())
                val kyberSignatureBase64 = encodeBase64(localKyber.signature)

                val kyberDto = DeviceKyberPreKeyDto(
                    deviceId = deviceId,
                    userId = userId,
                    keyId = localKyber.id,
                    publicKey = kyberPublicKeyBase64,
                    signature = kyberSignatureBase64,
                    isActive = true
                )
                supabaseClient.postgrest["device_kyber_prekeys"].upsert(kyberDto, onConflict = "device_id,key_id")
            }

            // 4. Upsert Available One-Time PreKeys
            val availableOpks = preKeyManager.getAvailableOneTimePreKeys(deviceId)
            val opkDtos = availableOpks.map { opk ->
                DeviceOneTimePreKeyDto(
                    deviceId = deviceId,
                    userId = userId,
                    keyId = opk.id,
                    publicKey = encodeBase64(opk.keyPair.publicKey.serialize()),
                    isConsumed = false
                )
            }

            if (opkDtos.isNotEmpty()) {
                supabaseClient.postgrest["device_one_time_prekeys"].upsert(opkDtos, onConflict = "device_id,key_id")
            }

            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun rotateSignedPreKey(userId: String, deviceId: String): Resource<Unit> {
        return try {
            val newSpk = preKeyManager.rotateSignedPreKey(deviceId)

            // Deactivate existing signed prekeys on server for this device
            supabaseClient.postgrest["device_signed_prekeys"].update(
                mapOf("is_active" to false)
            ) {
                filter {
                    eq("device_id", deviceId)
                    eq("user_id", userId)
                }
            }

            // Publish new signed prekey
            val spkPublicKeyBase64 = encodeBase64(newSpk.keyPair.publicKey.serialize())
            val spkSignatureBase64 = encodeBase64(newSpk.signature)

            val newSpkDto = DeviceSignedPreKeyDto(
                deviceId = deviceId,
                userId = userId,
                keyId = newSpk.id,
                publicKey = spkPublicKeyBase64,
                signature = spkSignatureBase64,
                isActive = true
            )
            supabaseClient.postgrest["device_signed_prekeys"].upsert(newSpkDto)

            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun replenishOneTimePreKeys(userId: String, deviceId: String): Resource<Int> {
        return try {
            val countGenerated = preKeyManager.replenishOneTimePreKeys(deviceId)
            if (countGenerated > 0) {
                val availableOpks = preKeyManager.getAvailableOneTimePreKeys(deviceId)
                val opkDtos = availableOpks.map { opk ->
                    DeviceOneTimePreKeyDto(
                        deviceId = deviceId,
                        userId = userId,
                        keyId = opk.id,
                        publicKey = encodeBase64(opk.keyPair.publicKey.serialize()),
                        isConsumed = false
                    )
                }
                supabaseClient.postgrest["device_one_time_prekeys"].upsert(opkDtos)
            }
            Resource.Success(countGenerated)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun claimPreKeyBundle(recipientDeviceId: String): Resource<RemotePreKeyBundle> {
        return try {
            val resultDto = supabaseClient.postgrest.rpc(
                function = "claim_prekey_bundle",
                parameters = mapOf("p_recipient_device_id" to recipientDeviceId)
            ).decodeAs<ClaimedPreKeyBundleDto>()

            val bundle = RemotePreKeyBundle(
                deviceId = resultDto.deviceId,
                userId = resultDto.userId,
                registrationId = resultDto.registrationId,
                identityPublicKeyBase64 = resultDto.identityKey,
                signedPreKeyId = resultDto.signedPreKeyId,
                signedPreKeyBase64 = resultDto.signedPreKey,
                signedPreKeySignatureBase64 = resultDto.signedPreKeySignature,
                oneTimePreKeyId = resultDto.oneTimePreKeyId,
                oneTimePreKeyBase64 = resultDto.oneTimePreKey,
                kyberPreKeyId = resultDto.kyberPreKeyId,
                kyberPreKeyBase64 = resultDto.kyberPreKey,
                kyberPreKeySignatureBase64 = resultDto.kyberPreKeySignature
            )

            Resource.Success(bundle)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun getRemoteUnconsumedPreKeyCount(deviceId: String): Resource<Int> {
        return try {
            val count = supabaseClient.postgrest.rpc(
                function = "get_unconsumed_one_time_prekey_count",
                parameters = mapOf("p_device_id" to deviceId)
            ).decodeAs<Int>()

            Resource.Success(count)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun sanitizeErrorMessage(e: Exception): String {
        val raw = e.message ?: return "PreKey synchronization error."
        return raw.substringBefore("\nURL:").substringBefore("\nHeaders:").trim()
    }
}
