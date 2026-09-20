package com.campuschat.app.core.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

enum class SecurityLevel {
    STRONGBOX,
    TEE,
    SOFTWARE,
    UNKNOWN
}

/**
 * Utility for detecting device hardware-backed security capabilities (StrongBox vs TEE vs Software).
 */
class KeystoreSecurityDetector(private val context: Context) {

    /**
     * Checks if the physical device supports StrongBox KeyStore (dedicated Hardware Security Module).
     * StrongBox is available on select Android 9+ (API 28+) devices with dedicated secure chips.
     */
    fun hasStrongBoxSupport(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } else {
            false
        }
    }

    /**
     * Inspects a KeyStore SecretKey to determine whether it is backed by secure hardware (TEE/StrongBox).
     */
    fun isInsideSecureHardware(secretKey: SecretKey): Boolean {
        return try {
            val factory = SecretKeyFactory.getInstance(secretKey.algorithm, "AndroidKeyStore")
            val keyInfo = factory.getKeySpec(secretKey, KeyInfo::class.java) as KeyInfo
            keyInfo.isInsideSecureHardware
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Determines the effective security level for a master key stored in Android KeyStore.
     */
    fun getEffectiveSecurityLevel(secretKey: SecretKey?): SecurityLevel {
        if (secretKey == null) return SecurityLevel.UNKNOWN
        val isHardware = isInsideSecureHardware(secretKey)
        return when {
            isHardware && hasStrongBoxSupport() -> SecurityLevel.STRONGBOX
            isHardware -> SecurityLevel.TEE
            else -> SecurityLevel.SOFTWARE
        }
    }
}
