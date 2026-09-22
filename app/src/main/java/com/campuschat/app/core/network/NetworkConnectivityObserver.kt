package com.campuschat.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class NetworkStatus {
    Available,
    Lost,
    Unavailable
}

interface NetworkConnectivityObserver {
    fun observeNetwork(): Flow<NetworkStatus>
    val isNetworkAvailable: StateFlow<Boolean>
}

class AndroidNetworkConnectivityObserver(
    private val context: Context
) : NetworkConnectivityObserver {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isNetworkAvailable = MutableStateFlow(checkInitialConnectivity())
    override val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private fun checkInitialConnectivity(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun observeNetwork(): Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                _isNetworkAvailable.value = true
                trySend(NetworkStatus.Available)
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                super.onLosing(network, maxMsToLive)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                _isNetworkAvailable.value = checkInitialConnectivity()
                if (!_isNetworkAvailable.value) {
                    trySend(NetworkStatus.Lost)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                _isNetworkAvailable.value = false
                trySend(NetworkStatus.Unavailable)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Send initial status
        if (checkInitialConnectivity()) {
            trySend(NetworkStatus.Available)
        } else {
            trySend(NetworkStatus.Lost)
        }

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Ignore unregister errors if app is shutting down
            }
        }
    }.distinctUntilChanged()
}
