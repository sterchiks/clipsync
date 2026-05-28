package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ClipServiceTracker {
    private val _serverIp = MutableStateFlow("Unknown")
    val serverIp: StateFlow<String> = _serverIp.asStateFlow()

    private val _pcIp = MutableStateFlow("")
    val pcIp: StateFlow<String> = _pcIp.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _lastReceivedText = MutableStateFlow("")
    val lastReceivedText: StateFlow<String> = _lastReceivedText.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<Set<String>>(emptySet())
    val discoveredPeers: StateFlow<Set<String>> = _discoveredPeers.asStateFlow()

    fun setServerIp(ip: String) {
        _serverIp.value = ip
    }

    fun setPcIp(ip: String) {
        _pcIp.value = ip
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun setLastReceivedText(text: String) {
        _lastReceivedText.value = text
    }

    fun addDiscoveredPeer(ip: String) {
        _discoveredPeers.value = _discoveredPeers.value + ip
    }

    fun clearDiscoveredPeers() {
        _discoveredPeers.value = emptySet()
    }
}
