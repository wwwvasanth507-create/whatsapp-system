package com.campuschat.app.core.device

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.campuschat.app.core.config.AppConfig
import java.util.UUID

object DeviceIdProvider {

    private const val PREFS_NAME = "campuschat_device_prefs"
    private const val KEY_DEVICE_ID = "key_installation_device_id"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets or creates a persistent UUID for this Android app installation.
     */
    fun getDeviceId(): String {
        if (!::prefs.isInitialized) {
            return "test-device-id"
        }
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId ?: UUID.randomUUID().toString()
    }

    /**
     * Returns a human-readable device name (e.g. "Google Pixel 7 (Android 14)").
     */
    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
        val model = Build.MODEL ?: "Device"
        val release = Build.VERSION.RELEASE ?: "14"
        return "$manufacturer $model (Android $release)"
    }

    fun getPlatform(): String = AppConfig.PLATFORM_NAME
}
