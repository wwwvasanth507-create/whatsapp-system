package com.campuschat.app.core.crypto

import android.content.Context
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

interface PreKeyManager {
    fun hasSignedPreKey(keyId: Int): Boolean
    fun hasPreKey(keyId: Int): Boolean
    fun getSignedPreKeyCount(): Int
    fun getPreKeyCount(): Int
}

class PreKeyManagerImpl(
    private val context: Context,
    private val cryptoKeyManager: CryptoKeyManager
) : PreKeyManager {

    override fun hasSignedPreKey(keyId: Int): Boolean = false
    override fun hasPreKey(keyId: Int): Boolean = false
    override fun getSignedPreKeyCount(): Int = 0
    override fun getPreKeyCount(): Int = 0
}
