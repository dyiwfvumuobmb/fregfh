package com.android.imeisettings.service

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.telephony.*
import android.util.Log
import com.android.imeisettings.data.repository.NetworkStateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Deep telephony monitoring using system-level APIs.
 * Requires: system signature, MODIFY_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE
 *
 * Monitors:
 * 1. TelephonyRegistry for ALL telephony events (not just PhoneStateListener)
 * 2. ServiceState changes at system level
 * 3. Cell info with privileged fields
 * 4. Data connection state transitions
 * 5. Call forwarding (SS) changes
 * 6. NITZ (network time) manipulation
 * 7. Emergency number list changes
 * 8. Carrier restriction changes
 * 9. Barring info changes
 * 10. Physical channel config (5G NR)
 */
object SystemTelephonyMonitor {

    private const val TAG = "SYS_TEL_MON"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNitzTime: Long = 0
    private var lastServiceState: String = ""
    private var lastDataNetworkType: Int = -1
    private var lastEmergencyNumberCount: Int = -1
    private val cellHistory = mutableListOf<CellSnapshot>()

    data class CellSnapshot(
        val timestamp: Long,
        val cellId: Long,
        val lac: Int,
        val mcc: String?,
        val mnc: String?,
        val signalDbm: Int,
        val networkType: Int,
        val isRegistered: Boolean,
        val channelNumber: Int
    )

    /**
     * Initialize deep telephony monitoring.
     */
    fun start(context: Context) {
        Log.i(TAG, "Starting system telephony monitor...")

        registerPrivilegedListener(context)
        startNitzMonitor(context)
        startBarringInfoMonitor(context)
        startPhysicalChannelMonitor(context)
        startEmergencyNumberMonitor(context)
        startCallForwardingMonitor(context)
    }

    /**
     * Register privileged PhoneStateListener with ALL events.
     */
    private fun registerPrivilegedListener(context: Context) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(),
                TelephonyCallback.CellInfoListener,
                TelephonyCallback.ServiceStateListener,
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.DataConnectionStateListener,
                TelephonyCallback.CellLocationListener,
                TelephonyCallback.DisplayInfoListener {

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    analyzeCellInfoPrivileged(cellInfo)
                }

                override fun onServiceStateChanged(serviceState: ServiceState) {
                    analyzeServiceStatePrivileged(serviceState)
                }

                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    analyzeSignalStrengthPrivileged(signalStrength)
                }

                override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                    analyzeDataConnectionPrivileged(state, networkType)
                }

                override fun onCellLocationChanged(location: CellLocation) {
                    analyzeCellLocationPrivileged(location)
                }

                override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                    analyzeDisplayInfoPrivileged(displayInfo)
                }
            }

            try {
                tm.registerTelephonyCallback(context.mainExecutor, callback)
                Log.i(TAG, "Privileged TelephonyCallback registered")
            } catch (e: Exception) {
                Log.w(TAG, "TelephonyCallback registration: ${e.message}")
            }
        }
    }

    /**
     * Analyze cell info with privileged access.
     * System apps see fields not available to normal apps.
     */
    private fun analyzeCellInfoPrivileged(cells: List<CellInfo>) {
        val threats = mutableListOf<String>()

        for (cell in cells) {
            if (!cell.isRegistered) continue

            val snapshot = when (cell) {
                is CellInfoLte -> {
                    val id = cell.cellIdentity
                    CellSnapshot(
                        System.currentTimeMillis(),
                        id.ci.toLong(),
                        id.tac,
                        id.mccString,
                        id.mncString,
                        cell.cellSignalStrength.dbm,
                        TelephonyManager.NETWORK_TYPE_LTE,
                        cell.isRegistered,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.earfcn else -1
                    )
                }
                is CellInfoGsm -> {
                    val id = cell.cellIdentity
                    CellSnapshot(
                        System.currentTimeMillis(),
                        id.cid.toLong(),
                        id.lac,
                        id.mccString,
                        id.mncString,
                        cell.cellSignalStrength.dbm,
                        TelephonyManager.NETWORK_TYPE_GSM,
                        cell.isRegistered,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.arfcn else -1
                    )
                }
                is CellInfoWcdma -> {
                    val id = cell.cellIdentity
                    CellSnapshot(
                        System.currentTimeMillis(),
                        id.cid.toLong(),
                        id.lac,
                        id.mccString,
                        id.mncString,
                        cell.cellSignalStrength.dbm,
                        TelephonyManager.NETWORK_TYPE_UMTS,
                        cell.isRegistered,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.uarfcn else -1
                    )
                }
                else -> null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) {
                val id = cell.cellIdentity as CellIdentityNr
                val ss = cell.cellSignalStrength as CellSignalStrengthNr
                val nci = id.nci
                val tac = id.tac
                val ssRsrp = ss.ssRsrp
                val ssRsrq = ss.ssRsrq
                val ssSinr = ss.ssSinr

                // 5G NR anomaly: extremely strong signal + poor quality
                if (ssRsrp > -50 && ssRsrq < -15) {
                    threats.add("FBS: 5G NR strong signal ($ssRsrp dBm) but poor quality (RSRQ=$ssRsrq)")
                }

                // NR cell with zero NCI — suspicious
                if (nci == 0L || nci == Long.MAX_VALUE) {
                    threats.add("FBS: 5G NR cell with invalid NCI=$nci")
                }
            }

            if (snapshot != null) {
                checkCellAnomalies(snapshot, threats)
                synchronized(cellHistory) {
                    cellHistory.add(snapshot)
                    // Keep last 100 entries
                    if (cellHistory.size > 100) cellHistory.removeAt(0)
                }
                // Feed to RILDefender cell state tracking
                val rilCell = RilDefenderEngine.CellState(
                    cellId = snapshot.cellId,
                    lac = snapshot.lac,
                    arfcn = snapshot.channelNumber,
                    mcc = snapshot.mcc,
                    mnc = snapshot.mnc,
                    signalStrength = snapshot.signalDbm,
                    networkType = snapshot.networkType,
                    timestamp = snapshot.timestamp
                )
                if (snapshot.isRegistered) {
                    RilDefenderEngine.updateCurrentCell(rilCell)
                } else {
                    RilDefenderEngine.addCellState(rilCell)
                }
            }
        }

        if (threats.isNotEmpty()) {
            val severity = if (threats.size >= 3) 85 else 60
            val desc = threats.joinToString("; ")
            NetworkStateTracker.forceForensicThreat(severity, "SysTel: $desc")
            Log.w(TAG, "Cell anomalies: $desc")
        }
    }

    /**
     * Cross-reference cell data with history to detect anomalies.
     */
    private fun checkCellAnomalies(current: CellSnapshot, threats: MutableList<String>) {
        synchronized(cellHistory) {
            if (cellHistory.isEmpty()) return

            val prev = cellHistory.last()
            val timeDiff = current.timestamp - prev.timestamp

            // Rapid cell changes (< 5 seconds between different cells)
            if (timeDiff < 5000 && current.cellId != prev.cellId) {
                threats.add("Rapid cell handover: ${prev.cellId} → ${current.cellId} in ${timeDiff}ms")
            }

            // Signal strength jump (> 30 dBm in one step)
            val sigDiff = kotlin.math.abs(current.signalDbm - prev.signalDbm)
            if (sigDiff > 30 && timeDiff < 10_000) {
                threats.add("Signal jump: ${prev.signalDbm} → ${current.signalDbm} dBm (${sigDiff}dB)")
            }

            // Network type downgrade
            val typeRank = mapOf(
                TelephonyManager.NETWORK_TYPE_GSM to 1,
                TelephonyManager.NETWORK_TYPE_GPRS to 2,
                TelephonyManager.NETWORK_TYPE_EDGE to 3,
                TelephonyManager.NETWORK_TYPE_UMTS to 4,
                TelephonyManager.NETWORK_TYPE_HSDPA to 5,
                TelephonyManager.NETWORK_TYPE_HSUPA to 5,
                TelephonyManager.NETWORK_TYPE_HSPA to 5,
                TelephonyManager.NETWORK_TYPE_HSPAP to 6,
                TelephonyManager.NETWORK_TYPE_LTE to 7,
                TelephonyManager.NETWORK_TYPE_NR to 8
            )
            val prevRank = typeRank[prev.networkType] ?: 0
            val currRank = typeRank[current.networkType] ?: 0
            if (currRank < prevRank - 1) {
                threats.add("Network downgrade: rank $prevRank → $currRank")
            }

            // LAC/TAC change without cell change
            if (current.cellId == prev.cellId && current.lac != prev.lac && current.lac > 0) {
                threats.add("LAC/TAC changed on same cell: ${prev.lac} → ${current.lac}")
            }

            // PLMN changed (MCC/MNC)
            if (current.mcc != prev.mcc || current.mnc != prev.mnc) {
                if (prev.mcc != null && current.mcc != null) {
                    threats.add("PLMN change: ${prev.mcc}/${prev.mnc} → ${current.mcc}/${current.mnc}")
                }
            }

            // ARFCN/EARFCN reuse with different cell ID — possible cell spoofing
            if (current.channelNumber > 0 && current.channelNumber == prev.channelNumber &&
                current.cellId != prev.cellId) {
                threats.add("Channel ${current.channelNumber} reused by different cell: ${prev.cellId} → ${current.cellId}")
            }
        }
    }

    /**
     * Analyze ServiceState with privileged fields.
     */
    private fun analyzeServiceStatePrivileged(state: ServiceState) {
        val stateStr = state.toString()
        if (stateStr == lastServiceState) return
        lastServiceState = stateStr

        // Check for emergency-only mode (can indicate FBS)
        try {
            val emMethod = state.javaClass.getMethod("isEmergencyOnly")
            val isEmergency = emMethod.invoke(state) as? Boolean ?: false
            if (isEmergency) {
                NetworkStateTracker.forceForensicThreat(50, "SysTel: Emergency-only service state")
            }
        } catch (_: Exception) {}

        // Check roaming anomaly (not expected to roam in home country)
        if (state.roaming) {
            Log.w(TAG, "Roaming detected — check PLMN consistency")
        }

        // Check for data registration state changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val nrMethod = state.javaClass.getMethod("getNrState")
                val nrState = nrMethod.invoke(state)
                Log.d(TAG, "ServiceState NR state: $nrState")
            } catch (_: Exception) {}
        }

        // Check CSS indicator (Concurrent Services Support)
        // False on GSM-only = legacy network, suspicious
        try {
            val cssMethod = state.javaClass.getDeclaredMethod("getCssIndicator")
            cssMethod.isAccessible = true
            val css = cssMethod.invoke(state) as? Int
            if (css == 0) {
                Log.d(TAG, "CSS=0: No concurrent services (legacy)")
            }
        } catch (_: Exception) {}

        // Check channel number via reflection
        try {
            val channelMethod = state.javaClass.getDeclaredMethod("getChannelNumber")
            channelMethod.isAccessible = true
            val channel = channelMethod.invoke(state) as? Int
            Log.d(TAG, "Channel number: $channel")
        } catch (_: Exception) {}
    }

    private fun analyzeSignalStrengthPrivileged(ss: SignalStrength) {
        val level = ss.level
        val dbm = getCellSignalDbm(ss)

        // Feed to RILDefender signal history (from SignalStrengthController patch)
        RilDefenderEngine.addSignalStrength(dbm)

        // System apps can get raw signal values
        if (dbm > -40) {
            NetworkStateTracker.forceForensicThreat(
                60,
                "SysTel: Extremely strong signal ${dbm}dBm (potential FBS proximity)"
            )
        }
    }

    private fun getCellSignalDbm(ss: SignalStrength): Int {
        return try {
            val cellSignals = ss.cellSignalStrengths
            cellSignals.firstOrNull { it.dbm != Int.MAX_VALUE }?.dbm ?: -999
        } catch (_: Exception) { -999 }
    }

    private fun analyzeDataConnectionPrivileged(state: Int, networkType: Int) {
        if (networkType != lastDataNetworkType) {
            if (lastDataNetworkType == TelephonyManager.NETWORK_TYPE_NR &&
                networkType == TelephonyManager.NETWORK_TYPE_LTE) {
                NetworkStateTracker.forceForensicThreat(
                    65, "SysTel: 5G→LTE data downgrade (potential forced handover)"
                )
            }
            if (lastDataNetworkType == TelephonyManager.NETWORK_TYPE_LTE &&
                (networkType == TelephonyManager.NETWORK_TYPE_GSM ||
                    networkType == TelephonyManager.NETWORK_TYPE_EDGE)) {
                NetworkStateTracker.forceForensicThreat(
                    80, "SysTel: LTE→2G data downgrade (HIGH RISK — cipher bypass)"
                )
            }
            lastDataNetworkType = networkType
        }
    }

    private fun analyzeCellLocationPrivileged(location: CellLocation) {
        Log.d(TAG, "Cell location changed: $location")
    }

    private fun analyzeDisplayInfoPrivileged(info: TelephonyDisplayInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val overrideType = info.overrideNetworkType
            Log.d(TAG, "Display info: networkType=${info.networkType}, override=$overrideType")
        }
    }

    // ==================== NITZ Monitor ====================

    /**
     * Monitor NITZ (Network Identity and Timezone).
     * FBS can send fake time/timezone to manipulate device.
     */
    private fun startNitzMonitor(context: Context) {
        scope.launch {
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "phone") as? IBinder ?: return@launch

                val iTelephonyStub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                val asInterface = iTelephonyStub.getMethod("asInterface", IBinder::class.java)
                val iTelephony = asInterface.invoke(null, binder) ?: return@launch

                for (m in iTelephony.javaClass.declaredMethods) {
                    if (m.name.contains("getNitzTime") || m.name.contains("getNetworkTime")) {
                        m.isAccessible = true
                        try {
                            val nitzTime = m.invoke(iTelephony) as? Long
                            if (nitzTime != null && lastNitzTime > 0) {
                                val drift = kotlin.math.abs(nitzTime - System.currentTimeMillis())
                                if (drift > 300_000) { // > 5 minutes drift
                                    NetworkStateTracker.forceForensicThreat(
                                        70,
                                        "NITZ time manipulation: drift=${drift / 1000}s"
                                    )
                                }
                            }
                            lastNitzTime = nitzTime ?: 0
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "NITZ monitor: ${e.message}")
            }
        }
    }

    // ==================== Barring Info Monitor ====================

    private fun startBarringInfoMonitor(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            try {
                val callback = object : TelephonyCallback(),
                    TelephonyCallback.BarringInfoListener {
                    override fun onBarringInfoChanged(barringInfo: BarringInfo) {
                        analyzeBarringInfo(barringInfo)
                    }
                }
                tm.registerTelephonyCallback(context.mainExecutor, callback)
                Log.i(TAG, "Barring info monitor registered")
            } catch (e: Exception) {
                Log.w(TAG, "Barring info monitor: ${e.message}")
            }
        }
    }

    private fun analyzeBarringInfo(info: BarringInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        // Check if call/data services are barred
        val serviceTypes = intArrayOf(
            BarringInfo.BARRING_SERVICE_TYPE_CS_SERVICE,
            BarringInfo.BARRING_SERVICE_TYPE_PS_SERVICE,
            BarringInfo.BARRING_SERVICE_TYPE_CS_VOICE,
            BarringInfo.BARRING_SERVICE_TYPE_MO_DATA,
            BarringInfo.BARRING_SERVICE_TYPE_EMERGENCY
        )

        val barred = mutableListOf<String>()
        for (type in serviceTypes) {
            val info2 = info.getBarringServiceInfo(type)
            if (info2.isBarred) {
                barred.add("type=$type")
            }
        }

        if (barred.isNotEmpty()) {
            NetworkStateTracker.forceForensicThreat(
                70,
                "SysTel: Services barred by network: ${barred.joinToString()}"
            )
        }
    }

    // ==================== Physical Channel Config (5G) ====================

    private fun startPhysicalChannelMonitor(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

                // System apps can access physical channel configuration
                for (m in tm.javaClass.declaredMethods) {
                    if (m.name.contains("getPhysicalChannelConfig")) {
                        m.isAccessible = true
                        try {
                            val configs = m.invoke(tm) as? List<*>
                            if (configs != null) {
                                for (config in configs) {
                                    Log.d(TAG, "Physical channel: $config")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Physical channel monitor: ${e.message}")
            }
        }
    }

    // ==================== Emergency Number Monitor ====================

    private fun startEmergencyNumberMonitor(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            try {
                val emergencyNumbers = tm.emergencyNumberList
                val count = emergencyNumbers.values.sumOf { it.size }
                if (lastEmergencyNumberCount > 0 && count != lastEmergencyNumberCount) {
                    NetworkStateTracker.forceForensicThreat(
                        60,
                        "SysTel: Emergency number list changed: $lastEmergencyNumberCount → $count"
                    )
                }
                lastEmergencyNumberCount = count
            } catch (e: Exception) {
                Log.d(TAG, "Emergency number monitor: ${e.message}")
            }
        }
    }

    // ==================== Call Forwarding Monitor ====================

    /**
     * Monitor call forwarding changes — FBS can silently enable call forwarding.
     */
    private fun startCallForwardingMonitor(context: Context) {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "phone") as? IBinder ?: return

            val iTelephonyStub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterface = iTelephonyStub.getMethod("asInterface", IBinder::class.java)
            val iTelephony = asInterface.invoke(null, binder) ?: return

            for (m in iTelephony.javaClass.declaredMethods) {
                if (m.name.contains("getCallForwarding") || m.name.contains("queryCFReasonForSubId")) {
                    m.isAccessible = true
                    try {
                        val result = when (m.parameterCount) {
                            1 -> m.invoke(iTelephony, 0) // reason = unconditional
                            2 -> m.invoke(iTelephony, 0, 0) // reason, subId
                            else -> null
                        }
                        if (result != null) {
                            Log.i(TAG, "Call forwarding status: $result")
                            // If forwarding is enabled and user didn't set it — threat
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Call forwarding monitor: ${e.message}")
        }
    }
}
