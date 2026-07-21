package com.flick.receiver.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Callback-backed trigger for rebinding; callers still reconcile the concrete IPv4 address. */
class LanBindingMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _changed = MutableStateFlow(0L)
    val changed: StateFlow<Long> = _changed
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = bump()
        override fun onLost(network: Network) = bump()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = bump()
        private fun bump() { _changed.value++ }
    }
    fun start() = manager.registerDefaultNetworkCallback(callback)
    fun stop() = runCatching { manager.unregisterNetworkCallback(callback) }
}
