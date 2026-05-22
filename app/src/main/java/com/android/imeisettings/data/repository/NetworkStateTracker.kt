package com.android.imeisettings.data.repository

import com.android.imeisettings.data.model.NetworkDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

object NetworkStateTracker {
    private val _networkDetailsSim1 = MutableStateFlow(NetworkDetails(voiceState = "INITIALIZING..."))
    val networkDetailsSim1: StateFlow<NetworkDetails> = _networkDetailsSim1

    private val _networkDetailsSim2 = MutableStateFlow(NetworkDetails(voiceState = "INITIALIZING..."))
    val networkDetailsSim2: StateFlow<NetworkDetails> = _networkDetailsSim2

    private val _totalThreatLevel = MutableStateFlow(-1)
    val totalThreatLevel: StateFlow<Int> = _totalThreatLevel

    private val _threatReason = MutableStateFlow<String?>(null)
    val threatReason: StateFlow<String?> = _threatReason

    private val _forensicThreatActive = AtomicBoolean(false)
    val forensicThreatActive: Boolean
        get() = _forensicThreatActive.get()

    // Connection uptime tracker — records when the current cell ID was first seen
    private var currentCellId1: String? = null
    private var cellConnectedSince1: Long = 0L
    private var currentCellId2: String? = null
    private var cellConnectedSince2: Long = 0L

    // Total monitoring uptime
    private val monitoringStartTime = System.currentTimeMillis()

    fun getMonitoringUptimeMs(): Long = System.currentTimeMillis() - monitoringStartTime
    fun getCellUptimeMs(slot: Int): Long {
        val since = if (slot == 0) cellConnectedSince1 else cellConnectedSince2
        return if (since > 0) System.currentTimeMillis() - since else 0L
    }

    fun resetThreatLevel() {
        _forensicThreatActive.set(false)
        _totalThreatLevel.value = 0
        _threatReason.value = null
    }

    fun updateSim1(details: NetworkDetails) {
        if (details.cellId != currentCellId1 && details.cellId != "---") {
            currentCellId1 = details.cellId
            cellConnectedSince1 = System.currentTimeMillis()
        }
        _networkDetailsSim1.value = details
    }
    fun updateSim2(details: NetworkDetails) {
        if (details.cellId != currentCellId2 && details.cellId != "---") {
            currentCellId2 = details.cellId
            cellConnectedSince2 = System.currentTimeMillis()
        }
        _networkDetailsSim2.value = details
    }
    
    fun updateThreatLevel(level: Int, reason: String? = null) {
        // If forensic threat is active, use max to avoid overwriting a higher forensic value
        if (_forensicThreatActive.get()) {
            _totalThreatLevel.value = maxOf(_totalThreatLevel.value, level)
        } else {
            _totalThreatLevel.value = level
        }
        if (reason != null) _threatReason.value = reason
    }
    
    fun forceForensicThreat(level: Int = 100, reason: String) {
        _forensicThreatActive.set(true)
        // Use max of current and new level to prevent downgrade by a weaker event
        val effective = maxOf(_totalThreatLevel.value, level).coerceAtMost(100)
        _totalThreatLevel.value = effective
        _threatReason.value = reason
    }

    fun resetForensicThreat() {
        _forensicThreatActive.set(false)
    }
}
