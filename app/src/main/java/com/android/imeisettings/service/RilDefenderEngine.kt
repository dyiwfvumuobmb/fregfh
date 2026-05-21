package com.android.imeisettings.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.telephony.*
import android.util.Log
import com.android.imeisettings.data.repository.NetworkStateTracker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RILDefender-inspired SMS attack detection engine.
 * Ported and extended from OSUSecLab/RILDefender (NDSS 2023).
 *
 * Features taken from RILDefender:
 * 1. YAML-like configurable attack signatures (RILSigEvaluator)
 * 2. CellState history with FBS detection via RSSI threshold + unusual cell params
 * 3. Proactive SIM SMS detection (SMS from telephony/SIM process)
 * 4. Malware SMS detection (SMS from untrusted apps/processes)
 * 5. Binary SMS auto-dial voice call blocking
 * 6. SMS source whitelist system
 * 7. Configurable alert levels: ALLOW / NOTIFY / BLOCK / BLOCK_AND_NOTIFY
 * 8. SMS event history with JSON logging
 * 9. Signal strength history with anomaly correlation
 */
object RilDefenderEngine {

    private const val TAG = "RIL_DEFENDER"
    private const val PREFS_NAME = "ril_defender_prefs"
    private const val SMS_LOG_FILE = "ril_defender_sms_log.json"
    private const val SIGNATURES_FILE = "ril_defender_signatures.yaml"

    // ═══════════════════════════════════════════
    // Alert levels (from RILDefender)
    // ═══════════════════════════════════════════
    enum class AlertLevel(val value: Int) {
        ALLOW(0),
        NOTIFY(1),
        BLOCK(2),
        BLOCK_AND_NOTIFY(3);

        companion object {
            fun fromInt(v: Int) = entries.firstOrNull { it.value == v } ?: ALLOW
        }
    }

    // Shared Preferences keys for each SMS attack type
    const val SP_SILENT_SMS = "silent_sms"
    const val SP_BINARY_SMS = "binary_sms"
    const val SP_FLASH_SMS = "flash_sms"
    const val SP_FBS_SMS = "fbs_sms"
    const val SP_MALWARE_SMS = "malware_sms"
    const val SP_PROACTIVE_SIM_SMS = "proactive_sim_sms"

    // RILDefender thresholds
    const val RSSI_THRESHOLD = -40 // dBm — signals stronger than this are suspicious
    private const val CELL_HISTORY_MAX = 200
    private const val SIGNAL_HISTORY_MAX = 500
    private const val SMS_HISTORY_MAX = 1000
    private const val RAPID_CELL_SWITCH_WINDOW_MS = 30_000L // 30 sec window
    private const val RAPID_CELL_SWITCH_THRESHOLD = 5 // switches within window
    private const val TMSI_REALLOC_THRESHOLD = 4 // reallocations within 60s

    // ═══════════════════════════════════════════
    // State tracking (from RILDefender)
    // ═══════════════════════════════════════════
    data class CellState(
        val cellId: Long,
        val lac: Int,
        val arfcn: Int,
        val mcc: String?,
        val mnc: String?,
        val operatorNumeric: String? = null,
        val operatorAlphaLong: String? = null,
        val operatorAlphaShort: String? = null,
        var signalStrength: Int = -999,
        var signalHistory: MutableList<Int> = mutableListOf(),
        val networkType: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isValid(): Boolean =
            cellId > 0 && cellId != Long.MAX_VALUE && lac > 0 && lac != Int.MAX_VALUE

        fun updateSignalStrength(ss: Int) {
            signalStrength = ss
            signalHistory.add(ss)
            if (signalHistory.size > 50) signalHistory.removeAt(0)
        }

        fun averageSignal(): Double =
            if (signalHistory.isEmpty()) signalStrength.toDouble()
            else signalHistory.average()
    }

    // Cell state history (like RILDefender.historyStates)
    val historyStates = CopyOnWriteArrayList<CellState>()
    var currentCellState: CellState? = null

    // Rapid cell switch tracking
    private val cellSwitchTimestamps = CopyOnWriteArrayList<Long>()

    // TMSI reallocation tracking
    private val tmsiReallocTimestamps = CopyOnWriteArrayList<Long>()

    // Signal strength history (like RILDefender.signalHistory)
    private val signalHistory = CopyOnWriteArrayList<Int>()

    // SMS PDU history (like RILDefender.smsHistory)
    private val smsHistory = CopyOnWriteArrayList<ByteArray>()

    // SMS event log
    private val smsEventLog = CopyOnWriteArrayList<JSONObject>()

    // SMS sender whitelist (like RILDefender.validSources)
    val trustedSmsSources = CopyOnWriteArrayList<String>().apply {
        add("com.android.messaging")
        add("com.google.android.apps.messaging")
        add("com.android.phone")
        add("com.android.mms")
    }

    // Trusted dialer apps (like RILDefender.trustedDialerAppName)
    val trustedDialerApps = CopyOnWriteArrayList<String>().apply {
        add("com.android.phone")
        add("com.android.dialer")
        add("com.google.android.dialer")
        add("com.android.server.telecom")
    }

    // Proactive SIM process name
    const val PROACTIVE_SIM_PROCESS = "com.android.phone"

    // Custom YAML signatures (like RILDefender.signatures)
    val customSignatures = ConcurrentHashMap<String, AttackSignature>()

    private var prefs: SharedPreferences? = null

    // ═══════════════════════════════════════════
    // Attack Signature System (from RILSigEvaluator)
    // ═══════════════════════════════════════════
    data class AttackSignature(
        val name: String,
        val conditions: List<SignatureCondition>,
        val securityLevel: Int = AlertLevel.BLOCK_AND_NOTIFY.value
    )

    data class SignatureCondition(
        val field: String,    // e.g., "sms.pid", "sms.dcs", "bs.ss", "bs.param"
        val operator: String, // ==, !=, >, <, >=, <=, contains
        val value: String
    )

    // ═══════════════════════════════════════════
    // Initialization
    // ═══════════════════════════════════════════
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Set default alert levels if not configured
        if (!prefs!!.contains(SP_SILENT_SMS)) {
            prefs!!.edit().apply {
                putInt(SP_SILENT_SMS, AlertLevel.BLOCK_AND_NOTIFY.value)
                putInt(SP_BINARY_SMS, AlertLevel.BLOCK_AND_NOTIFY.value)
                putInt(SP_FLASH_SMS, AlertLevel.NOTIFY.value)
                putInt(SP_FBS_SMS, AlertLevel.BLOCK_AND_NOTIFY.value)
                putInt(SP_MALWARE_SMS, AlertLevel.BLOCK_AND_NOTIFY.value)
                putInt(SP_PROACTIVE_SIM_SMS, AlertLevel.BLOCK_AND_NOTIFY.value)
                apply()
            }
        }

        // Load custom signatures from file
        loadCustomSignatures(context)

        // Load SMS whitelist
        loadWhitelist(context)

        Log.i(TAG, "RILDefender engine initialized. Signatures: ${customSignatures.size}")
    }

    // ═══════════════════════════════════════════
    // Alert level getters/setters
    // ═══════════════════════════════════════════
    fun getAlertLevel(type: String): AlertLevel {
        val value = prefs?.getInt(type, AlertLevel.BLOCK_AND_NOTIFY.value)
            ?: AlertLevel.BLOCK_AND_NOTIFY.value
        return AlertLevel.fromInt(value)
    }

    fun setAlertLevel(type: String, level: AlertLevel) {
        prefs?.edit()?.putInt(type, level.value)?.apply()
    }

    // ═══════════════════════════════════════════
    // Cell State Management (from RILDefender ServiceStateTracker patch)
    // ═══════════════════════════════════════════
    fun addCellState(state: CellState) {
        if (!state.isValid()) return

        // Check if already in history
        val existing = historyStates.find { it.cellId == state.cellId && it.lac == state.lac }
        if (existing != null) {
            existing.updateSignalStrength(state.signalStrength)
            return
        }

        historyStates.add(state)
        if (historyStates.size > CELL_HISTORY_MAX) {
            historyStates.removeAt(0)
        }

        Log.d(TAG, "New cell: CID=${state.cellId} LAC=${state.lac} SS=${state.signalStrength}")
    }

    fun updateCurrentCell(state: CellState) {
        currentCellState = state
        addCellState(state)
        clearSignalHistory()
    }

    // ═══════════════════════════════════════════
    // Signal Strength Management (from RILDefender SignalStrengthController patch)
    // ═══════════════════════════════════════════
    fun addSignalStrength(dbm: Int) {
        signalHistory.add(dbm)
        if (signalHistory.size > SIGNAL_HISTORY_MAX) {
            signalHistory.removeAt(0)
        }
        currentCellState?.updateSignalStrength(dbm)
    }

    fun getAverageRSSI(): Double {
        if (signalHistory.isEmpty()) return -999.0
        return signalHistory.average()
    }

    private fun clearSignalHistory() {
        signalHistory.clear()
    }

    // ═══════════════════════════════════════════
    // FBS Detection (from RILDefender InboundSmsHandler patch)
    // ═══════════════════════════════════════════
    fun detectFBS(): Boolean {
        val cs = currentCellState ?: return false

        // Check 1: RSSI above threshold (like RILDefender: ss > RSSI_THRESHOLD)
        // Positive dBm values are physically abnormal and indicate FBS proximity
        if (cs.signalStrength >= 0 || (cs.signalStrength < 0 && cs.signalStrength > RSSI_THRESHOLD)) {
            Log.w(TAG, "FBS: Signal too strong: ${cs.signalStrength} dBm (threshold: $RSSI_THRESHOLD)")
            return true
        }

        // Check 2: Unusual cell parameters
        if (detectUnusualCellParam()) {
            return true
        }

        // Check 3: Rapid cell switching — FBS causes frequent handovers
        if (detectRapidCellSwitching()) {
            Log.w(TAG, "FBS: Rapid cell switching detected")
            NetworkStateTracker.forceForensicThreat(75, "RILDefender: Rapid cell switching (possible IMSI catcher)")
            return true
        }

        // Check 4: Excessive TMSI reallocations — IMSI catchers frequently force TMSI realloc
        if (detectExcessiveTmsiRealloc()) {
            Log.w(TAG, "FBS: Excessive TMSI reallocation detected")
            NetworkStateTracker.forceForensicThreat(80, "RILDefender: Excessive TMSI reallocation")
            return true
        }

        // Check 5: Signal strength variance anomaly — stable FBS vs natural fluctuation
        if (detectLowSignalVariance(cs)) {
            Log.w(TAG, "FBS: Unnaturally stable signal detected")
            return true
        }

        return false
    }

    fun recordCellSwitch() {
        val now = System.currentTimeMillis()
        cellSwitchTimestamps.add(now)
        cellSwitchTimestamps.removeAll { now - it > RAPID_CELL_SWITCH_WINDOW_MS }
    }

    fun recordTmsiReallocation() {
        val now = System.currentTimeMillis()
        tmsiReallocTimestamps.add(now)
        tmsiReallocTimestamps.removeAll { now - it > 60_000L }
    }

    private fun detectRapidCellSwitching(): Boolean {
        val now = System.currentTimeMillis()
        val recentSwitches = cellSwitchTimestamps.count { now - it < RAPID_CELL_SWITCH_WINDOW_MS }
        return recentSwitches >= RAPID_CELL_SWITCH_THRESHOLD
    }

    private fun detectExcessiveTmsiRealloc(): Boolean {
        val now = System.currentTimeMillis()
        val recentReallocs = tmsiReallocTimestamps.count { now - it < 60_000L }
        return recentReallocs >= TMSI_REALLOC_THRESHOLD
    }

    private fun detectLowSignalVariance(cs: CellState): Boolean {
        if (cs.signalHistory.size < 10) return false
        val recent = cs.signalHistory.takeLast(10)
        val avg = recent.average()
        val variance = recent.map { (it - avg) * (it - avg) }.average()
        // Real towers have natural signal variation; FBS emit very stable signals
        return variance < 0.5 && avg > -70 && avg < -20
    }

    /**
     * Detect unusual cell parameters (from RILDefender).
     * Checks:
     * - Cell ID not seen before in history
     * - ARFCN reuse with different CID
     * - Operator mismatch with SIM
     * - LAC/TAC anomaly
     */
    fun detectUnusualCellParam(): Boolean {
        val cs = currentCellState ?: return false
        val threats = mutableListOf<String>()

        // Check if this cell was ever seen before
        val everSeen = historyStates.any {
            it.cellId == cs.cellId && it.lac == cs.lac && it.mcc == cs.mcc && it.mnc == cs.mnc
        }
        if (!everSeen && historyStates.size > 10) {
            // We have enough history — this is a brand new cell
            threats.add("New cell not in history: CID=${cs.cellId}")
        }

        // ARFCN reuse — same frequency different cell (cell spoofing indicator)
        if (cs.arfcn > 0) {
            val arfcnConflict = historyStates.any {
                it.arfcn == cs.arfcn && it.cellId != cs.cellId && it.isValid()
            }
            if (arfcnConflict) {
                threats.add("ARFCN ${cs.arfcn} reused by different CID=${cs.cellId}")
            }
        }

        // Operator numeric mismatch — different MCC/MNC from what we expect
        if (cs.operatorNumeric != null && historyStates.isNotEmpty()) {
            val prevOp = historyStates.lastOrNull()?.operatorNumeric
            if (prevOp != null && prevOp != cs.operatorNumeric) {
                threats.add("Operator changed: $prevOp → ${cs.operatorNumeric}")
            }
        }

        // Very small LAC (often used by FBS which don't bother with realistic values)
        if (cs.lac in 1..2) {
            threats.add("Suspicious LAC value: ${cs.lac}")
        }

        if (threats.isNotEmpty()) {
            val desc = threats.joinToString("; ")
            Log.w(TAG, "Unusual cell params: $desc")
            NetworkStateTracker.forceForensicThreat(70, "RILDefender: $desc")
            return true
        }

        return false
    }

    // ═══════════════════════════════════════════
    // SMS Analysis (from RILDefender GsmInboundSmsHandler patch)
    // ═══════════════════════════════════════════

    data class SmsVerdict(
        val type: String,
        val alertLevel: AlertLevel,
        val shouldBlock: Boolean,
        val shouldNotify: Boolean,
        val description: String,
        val source: String? = null,
        val pdu: ByteArray? = null
    )

    /**
     * Analyze incoming SMS like RILDefender does at the RIL layer.
     * Returns a verdict with action to take.
     */
    fun analyzeSms(
        pdu: ByteArray,
        isTypeZero: Boolean,
        isClassZero: Boolean,
        isUsimDataDownload: Boolean,
        originAddress: String?,
        messageBody: String?,
        protocolId: Int,
        dataCodingScheme: Int,
        callerProcessName: String? = null
    ): SmsVerdict {
        // Record SMS in history
        addSms(pdu)

        // 1. Silent SMS (Type-0) — like RILDefender GsmInboundSmsHandler
        if (isTypeZero) {
            val level = getAlertLevel(SP_SILENT_SMS)
            return SmsVerdict(
                type = SP_SILENT_SMS,
                alertLevel = level,
                shouldBlock = level.value >= AlertLevel.BLOCK.value,
                shouldNotify = level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
                description = "Silent SMS (Type-0) from $originAddress",
                source = originAddress,
                pdu = pdu
            )
        }

        // 2. Flash SMS (Class-0) — like RILDefender isClassZero()
        if (isClassZero) {
            val level = getAlertLevel(SP_FLASH_SMS)
            return SmsVerdict(
                type = SP_FLASH_SMS,
                alertLevel = level,
                shouldBlock = level.value >= AlertLevel.BLOCK.value,
                shouldNotify = level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
                description = "Flash SMS (Class-0) from $originAddress",
                source = originAddress,
                pdu = pdu
            )
        }

        // 3. Binary SMS / USIM Data Download — like RILDefender CatService patch
        if (isUsimDataDownload || protocolId == 0x7F || protocolId == 0x7C ||
            protocolId == 0x40 || protocolId == 0x41 || protocolId == 0x42) {
            val level = getAlertLevel(SP_BINARY_SMS)
            val pidDesc = when (protocolId) {
                0x7F -> "Return Call"
                0x7C -> "ME Data Download"
                0x40 -> "SIM Data Download"
                0x41 -> "ME Data Download"
                0x42 -> "USIM Data Download"
                else -> "PID=0x${protocolId.toString(16)}"
            }
            return SmsVerdict(
                type = SP_BINARY_SMS,
                alertLevel = level,
                shouldBlock = level.value >= AlertLevel.BLOCK.value,
                shouldNotify = level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
                description = "Binary SMS ($pidDesc) from $originAddress",
                source = originAddress,
                pdu = pdu
            )
        }

        // 4. FBS SMS detection — like RILDefender InboundSmsHandler patch
        if (detectFBS()) {
            val level = getAlertLevel(SP_FBS_SMS)
            return SmsVerdict(
                type = SP_FBS_SMS,
                alertLevel = level,
                shouldBlock = level.value >= AlertLevel.BLOCK.value,
                shouldNotify = level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
                description = "SMS from suspected FBS (SS=${currentCellState?.signalStrength}dBm, CID=${currentCellState?.cellId})",
                source = originAddress,
                pdu = pdu
            )
        }

        // 5. Proactive SIM SMS — like RILDefender GsmSMSDispatcher patch
        if (callerProcessName == PROACTIVE_SIM_PROCESS && originAddress == null) {
            val level = getAlertLevel(SP_PROACTIVE_SIM_SMS)
            return SmsVerdict(
                type = SP_PROACTIVE_SIM_SMS,
                alertLevel = level,
                shouldBlock = level.value >= AlertLevel.BLOCK.value,
                shouldNotify = level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
                description = "Proactive SIM SMS from telephony process",
                source = "SIM",
                pdu = pdu
            )
        }

        // 6. Malware SMS — like RILDefender: SMS from untrusted process
        if (callerProcessName != null && !trustedSmsSources.contains(callerProcessName)) {
            val level = getAlertLevel(SP_MALWARE_SMS)
            return SmsVerdict(
                type = SP_MALWARE_SMS,
                alertLevel = level,
                shouldBlock = level.value >= AlertLevel.BLOCK.value,
                shouldNotify = level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
                description = "SMS from untrusted process: $callerProcessName",
                source = callerProcessName,
                pdu = pdu
            )
        }

        // 7. Custom YAML signatures — like RILDefender RILSigEvaluator
        for ((name, sig) in customSignatures) {
            if (evaluateSignature(sig, pdu, protocolId, dataCodingScheme, originAddress, messageBody)) {
                val level = AlertLevel.fromInt(sig.securityLevel)
                return SmsVerdict(
                    type = name,
                    alertLevel = level,
                    shouldBlock = level.value >= AlertLevel.BLOCK.value,
                    shouldNotify = level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
                    description = "Custom rule '$name' matched",
                    source = originAddress,
                    pdu = pdu
                )
            }
        }

        // No threat detected
        return SmsVerdict(
            type = "clean",
            alertLevel = AlertLevel.ALLOW,
            shouldBlock = false,
            shouldNotify = false,
            description = "SMS passed all checks",
            source = originAddress,
            pdu = pdu
        )
    }

    /**
     * Analyze outgoing SMS like RILDefender GsmSMSDispatcher.
     * Checks if the sending app is trusted.
     */
    fun analyzeOutgoingSms(
        context: Context,
        pdu: ByteArray,
        destAddress: String?,
        content: String?
    ): SmsVerdict {
        addSms(pdu)

        val callerPid = Binder.getCallingPid()
        val callerName = getAppNameByPid(context, callerPid)

        // Check whitelist
        if (callerName != null && trustedSmsSources.contains(callerName)) {
            return SmsVerdict("clean", AlertLevel.ALLOW, false, false,
                "Outgoing SMS from trusted: $callerName")
        }

        // Proactive SIM SMS
        if (callerName == PROACTIVE_SIM_PROCESS) {
            val level = getAlertLevel(SP_PROACTIVE_SIM_SMS)
            logSmsEvent(SP_PROACTIVE_SIM_SMS, "SIM", destAddress, content, pdu, level)
            return SmsVerdict(
                SP_PROACTIVE_SIM_SMS, level,
                level.value >= AlertLevel.BLOCK.value,
                level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
                "Proactive SIM SMS to $destAddress from $callerName",
                source = callerName, pdu = pdu
            )
        }

        // Malware SMS — untrusted sender
        val level = getAlertLevel(SP_MALWARE_SMS)
        logSmsEvent(SP_MALWARE_SMS, callerName ?: "unknown", destAddress, content, pdu, level)
        return SmsVerdict(
            SP_MALWARE_SMS, level,
            level.value >= AlertLevel.BLOCK.value,
            level.value == AlertLevel.NOTIFY.value || level.value == AlertLevel.BLOCK_AND_NOTIFY.value,
            "Outgoing SMS from untrusted: $callerName → $destAddress",
            source = callerName, pdu = pdu
        )
    }

    /**
     * Analyze voice call origin like RILDefender GsmCdmaPhone.
     * Returns true if call should be allowed, false if suspicious.
     */
    fun analyzeVoiceCallOrigin(context: Context): Boolean {
        val callerPid = Binder.getCallingPid()
        val callerName = getAppNameByPid(context, callerPid) ?: return false

        if (trustedDialerApps.contains(callerName)) {
            Log.d(TAG, "Voice call from trusted dialer: $callerName")
            return true
        }

        Log.w(TAG, "Voice call from untrusted app: $callerName (PID=$callerPid)")
        NetworkStateTracker.forceForensicThreat(
            80, "RILDefender: Voice call from untrusted app: $callerName"
        )
        return false
    }

    // ═══════════════════════════════════════════
    // SMS History (from RILDefender)
    // ═══════════════════════════════════════════
    private fun addSms(pdu: ByteArray) {
        smsHistory.add(pdu)
        if (smsHistory.size > SMS_HISTORY_MAX) {
            smsHistory.removeAt(0)
        }
    }

    fun getSmsCount(): Int = smsHistory.size

    // ═══════════════════════════════════════════
    // SMS Event Logging (from RILDefender FileUtil)
    // ═══════════════════════════════════════════
    fun logSmsEvent(
        type: String,
        source: String?,
        dest: String?,
        content: String?,
        pdu: ByteArray?,
        level: AlertLevel
    ) {
        try {
            val event = JSONObject().apply {
                put("type", type)
                put("source", source ?: "unknown")
                put("dest", dest ?: "unknown")
                put("content", content ?: "")
                put("pdu_hex", pdu?.joinToString("") { "%02X".format(it) } ?: "")
                put("security_level", level.name)
                put("timestamp", System.currentTimeMillis())
                put("cell_id", currentCellState?.cellId ?: -1)
                put("signal_dbm", currentCellState?.signalStrength ?: -999)
            }
            smsEventLog.add(event)
            Log.i(TAG, "SMS event logged: $type from $source")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log SMS event: ${e.message}")
        }
    }

    fun getSmsEventLog(): JSONArray {
        val arr = JSONArray()
        for (event in smsEventLog) {
            arr.put(event)
        }
        return arr
    }

    fun saveSmsEventLog(context: Context) {
        try {
            val file = File(context.filesDir, SMS_LOG_FILE)
            file.writeText(getSmsEventLog().toString(2))
            Log.i(TAG, "SMS event log saved: ${smsEventLog.size} events")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save SMS log: ${e.message}")
        }
    }

    fun loadSmsEventLog(context: Context) {
        try {
            val file = File(context.filesDir, SMS_LOG_FILE)
            if (file.exists()) {
                val json = JSONArray(file.readText())
                for (i in 0 until json.length()) {
                    smsEventLog.add(json.getJSONObject(i))
                }
                Log.i(TAG, "Loaded ${smsEventLog.size} SMS events from log")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load SMS log: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════
    // YAML Signature Evaluator (from RILDefender RILSigEvaluator)
    // ═══════════════════════════════════════════
    private fun evaluateSignature(
        sig: AttackSignature,
        pdu: ByteArray,
        pid: Int,
        dcs: Int,
        source: String?,
        body: String?
    ): Boolean {
        for (condition in sig.conditions) {
            val fieldValue = getFieldValue(condition.field, pdu, pid, dcs, source, body)
            if (!evaluateCondition(fieldValue, condition.operator, condition.value)) {
                return false // All conditions must match
            }
        }
        return sig.conditions.isNotEmpty()
    }

    private fun getFieldValue(
        field: String, pdu: ByteArray, pid: Int, dcs: Int, source: String?, body: String?
    ): String {
        return when (field.lowercase()) {
            "sms.pid" -> pid.toString()
            "sms.dcs" -> dcs.toString()
            "sms.src", "sms.source" -> source ?: ""
            "sms.body", "sms.content" -> body ?: ""
            "sms.length" -> (body?.length ?: 0).toString()
            "sms.pdu_length" -> pdu.size.toString()
            "bs.ss", "cell.signal" -> (currentCellState?.signalStrength ?: -999).toString()
            "bs.cid", "cell.id" -> (currentCellState?.cellId ?: -1).toString()
            "bs.lac", "cell.lac" -> (currentCellState?.lac ?: -1).toString()
            "bs.arfcn", "cell.arfcn" -> (currentCellState?.arfcn ?: -1).toString()
            "bs.mcc", "cell.mcc" -> currentCellState?.mcc ?: ""
            "bs.mnc", "cell.mnc" -> currentCellState?.mnc ?: ""
            "pdu.byte0" -> if (pdu.isNotEmpty()) (pdu[0].toInt() and 0xFF).toString() else "-1"
            "pdu.byte1" -> if (pdu.size > 1) (pdu[1].toInt() and 0xFF).toString() else "-1"
            else -> ""
        }
    }

    private fun evaluateCondition(actual: String, op: String, expected: String): Boolean {
        return try {
            when (op) {
                "==" -> actual == expected
                "!=" -> actual != expected
                ">" -> actual.toDoubleOrNull()?.let { it > expected.toDouble() } ?: false
                "<" -> actual.toDoubleOrNull()?.let { it < expected.toDouble() } ?: false
                ">=" -> actual.toDoubleOrNull()?.let { it >= expected.toDouble() } ?: false
                "<=" -> actual.toDoubleOrNull()?.let { it <= expected.toDouble() } ?: false
                "contains" -> actual.contains(expected, ignoreCase = true)
                "startswith" -> actual.startsWith(expected, ignoreCase = true)
                "regex" -> actual.matches(Regex(expected))
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════
    // Signature & Whitelist loading
    // ═══════════════════════════════════════════
    private fun loadCustomSignatures(context: Context) {
        try {
            val file = File(context.filesDir, SIGNATURES_FILE)
            if (!file.exists()) {
                // Create default signatures
                createDefaultSignatures(context)
                return
            }
            parseSignaturesYaml(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load signatures: ${e.message}")
            createDefaultSignatures(context)
        }
    }

    private fun createDefaultSignatures(context: Context) {
        // Built-in signatures inspired by RILDefender's a.yaml
        customSignatures["SIMJacker_PID"] = AttackSignature(
            name = "SIMJacker PID",
            conditions = listOf(
                SignatureCondition("sms.pid", "==", "0x40")
            ),
            securityLevel = AlertLevel.BLOCK_AND_NOTIFY.value
        )
        customSignatures["WIBAttack_PID"] = AttackSignature(
            name = "WIBAttack PID",
            conditions = listOf(
                SignatureCondition("sms.pid", "==", "0x41")
            ),
            securityLevel = AlertLevel.BLOCK_AND_NOTIFY.value
        )
        customSignatures["ReturnCall_Abuse"] = AttackSignature(
            name = "Return Call Abuse",
            conditions = listOf(
                SignatureCondition("sms.pid", "==", "0x7F")
            ),
            securityLevel = AlertLevel.BLOCK_AND_NOTIFY.value
        )
        customSignatures["FBS_StrongSignal"] = AttackSignature(
            name = "FBS Strong Signal",
            conditions = listOf(
                SignatureCondition("bs.ss", ">", RSSI_THRESHOLD.toString())
            ),
            securityLevel = AlertLevel.NOTIFY.value
        )
        customSignatures["UsimDataDownload"] = AttackSignature(
            name = "USIM Data Download",
            conditions = listOf(
                SignatureCondition("sms.pid", "==", "0x42")
            ),
            securityLevel = AlertLevel.BLOCK_AND_NOTIFY.value
        )

        // Save defaults
        saveSignatures(context)
    }

    /**
     * Parse YAML-like signature definitions.
     * Format compatible with RILDefender's a.yaml:
     *
     * RuleName:
     *   field: sms.pid
     *   condition: ==
     *   value: 0x20
     *   securityLevel: 3
     */
    fun parseSignaturesYaml(yaml: String) {
        val lines = yaml.lines()
        var currentRule: String? = null
        var field: String? = null
        var condition: String? = null
        var value: String? = null
        var secLevel = AlertLevel.BLOCK_AND_NOTIFY.value

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (!trimmed.startsWith("{") && trimmed.endsWith(":") && !trimmed.contains("lvalue")
                && !trimmed.contains("rvalue") && !trimmed.contains("condition")
                && !trimmed.contains("securityLevel")) {
                // New rule name
                if (currentRule != null && field != null && condition != null && value != null) {
                    customSignatures[currentRule] = AttackSignature(
                        currentRule, listOf(SignatureCondition(field, condition, value)), secLevel
                    )
                }
                currentRule = trimmed.removeSuffix(":").trim()
                field = null; condition = null; value = null
                secLevel = AlertLevel.BLOCK_AND_NOTIFY.value
            } else if (trimmed.contains("lvalue") || trimmed.contains("field")) {
                field = extractYamlValue(trimmed)
            } else if (trimmed.contains("condition")) {
                condition = extractYamlValue(trimmed)
            } else if (trimmed.contains("rvalue") || (trimmed.contains("value") && !trimmed.contains("lvalue"))) {
                value = extractYamlValue(trimmed)
            } else if (trimmed.contains("securityLevel")) {
                secLevel = extractYamlValue(trimmed).toIntOrNull() ?: AlertLevel.BLOCK_AND_NOTIFY.value
            }
        }

        // Last rule
        if (currentRule != null && field != null && condition != null && value != null) {
            customSignatures[currentRule] = AttackSignature(
                currentRule, listOf(SignatureCondition(field, condition, value)), secLevel
            )
        }

        Log.i(TAG, "Parsed ${customSignatures.size} custom signatures")
    }

    private fun extractYamlValue(line: String): String {
        val parts = line.split(":", limit = 2)
        if (parts.size < 2) return ""
        var v = parts[1].trim()
        // Remove quotes, brackets
        v = v.trim('\'', '"', '[', ']', '{', '}', ' ')
        return v
    }

    fun saveSignatures(context: Context) {
        try {
            val sb = StringBuilder()
            sb.appendLine("# RILDefender-style attack signatures")
            sb.appendLine("# Format: RuleName → field / condition / value / securityLevel")
            sb.appendLine()
            for ((name, sig) in customSignatures) {
                sb.appendLine("$name:")
                for (c in sig.conditions) {
                    sb.appendLine("  field: ${c.field}")
                    sb.appendLine("  condition: ${c.operator}")
                    sb.appendLine("  value: ${c.value}")
                }
                sb.appendLine("  securityLevel: ${sig.securityLevel}")
                sb.appendLine()
            }
            File(context.filesDir, SIGNATURES_FILE).writeText(sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save signatures: ${e.message}")
        }
    }

    fun addCustomSignature(name: String, field: String, op: String, value: String, level: Int) {
        customSignatures[name] = AttackSignature(
            name, listOf(SignatureCondition(field, op, value)), level
        )
    }

    // ═══════════════════════════════════════════
    // Whitelist management (from RILDefender SettingsActivity)
    // ═══════════════════════════════════════════
    private fun loadWhitelist(context: Context) {
        val saved = prefs?.getString("sms_whitelist", null)
        if (saved != null) {
            trustedSmsSources.clear()
            saved.split(";").filter { it.isNotBlank() }.forEach { trustedSmsSources.add(it.trim()) }
        }
    }

    fun updateWhitelist(context: Context, whitelist: List<String>) {
        trustedSmsSources.clear()
        trustedSmsSources.addAll(whitelist)
        prefs?.edit()?.putString("sms_whitelist", whitelist.joinToString(";"))?.apply()
    }

    fun addToWhitelist(packageName: String) {
        if (!trustedSmsSources.contains(packageName)) {
            trustedSmsSources.add(packageName)
        }
    }

    // ═══════════════════════════════════════════
    // Process identification (from RILDefender ProcessUtil)
    // ═══════════════════════════════════════════
    private fun getAppNameByPid(context: Context, pid: Int): String? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val processes = am.runningAppProcesses ?: return null
            processes.find { it.pid == pid }?.processName
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════
    // Statistics
    // ═══════════════════════════════════════════
    fun getStats(): JSONObject {
        return JSONObject().apply {
            put("cells_seen", historyStates.size)
            put("current_cell", currentCellState?.cellId ?: -1)
            put("current_signal", currentCellState?.signalStrength ?: -999)
            put("avg_rssi", getAverageRSSI())
            put("sms_analyzed", getSmsCount())
            put("events_logged", smsEventLog.size)
            put("custom_signatures", customSignatures.size)
            put("trusted_sources", trustedSmsSources.size)
            put("fbs_detected", detectFBS())
        }
    }
}
