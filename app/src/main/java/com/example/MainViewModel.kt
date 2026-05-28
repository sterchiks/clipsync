package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ClipDatabase
import com.example.data.ClipItem
import com.example.data.ClipServiceTracker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = ClipDatabase.getDatabase(context)
    private val clipDao = database.clipDao

    // Flows linked to the background service thread states
    val isServiceRunning: StateFlow<Boolean> = ClipServiceTracker.isServiceRunning
    val serverIp: StateFlow<String> = ClipServiceTracker.serverIp
    val pcIp: StateFlow<String> = ClipServiceTracker.pcIp
    val discoveredPeers: StateFlow<Set<String>> = ClipServiceTracker.discoveredPeers
    val lastReceivedText: StateFlow<String> = ClipServiceTracker.lastReceivedText

    // Historical sync records flow
    val clipHistory: StateFlow<List<ClipItem>> = clipDao.getAllClips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleService() {
        if (isServiceRunning.value) {
            val intent = Intent(context, ClipSyncService::class.java)
            context.stopService(intent)
        } else {
            val intent = Intent(context, ClipSyncService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun updatePcIp(ip: String) {
        ClipServiceTracker.setPcIp(ip)
    }

    fun sendClipboardToPc() {
        val activePcIp = pcIp.value
        if (activePcIp.isNotEmpty()) {
            ClipboardHelperActivity.startForPasteAndSend(context, activePcIp)
        }
    }

    fun deleteHistoryItem(item: ClipItem) {
        viewModelScope.launch {
            clipDao.deleteClip(item)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            clipDao.clearAllClips()
        }
    }

    fun copyTextToClipboard(text: String) {
        ClipboardHelperActivity.startForCopy(context, text)
    }
}
