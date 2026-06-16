package com.android.imeisettings.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.imeisettings.MainActivity
import com.android.imeisettings.R
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.SecurityLog
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.data.repository.NetworkStateTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive PDU Interception & Monitoring Service.
 *
 * Features:
 * 1. PDU analysis/block statistics from PduReceiver
 * 2. Silent Call Detection — monitors call state for zero-duration and silent calls
 * 3. Periodic SMS Database Scanning — checks SMS inbox for suspicious patterns
 * 4. RadioLogMonitor Integration — starts/stops radio log monitoring based on settings
 * 5. Content Observer — real-time monitoring of new SMS arrivals in the SMS database
 */
class PduInterceptorService : Service() {

    companion object {
        private const val CHANNEL_ID = "PduInterceptorChannel"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "PduInterceptorService"
        const val ACTION_PDU_BLOCKED = "com.android.imeisettings.PDU_BLOCKED"
        const val ACTION_PDU_ANALYZED = "com.android.imeisettings.PDU_ANALYZED"
        const val ACTION_QUICK_ROTATE = "com.android.imeisettings.QUICK_ROTATE"
        const val ACTION_PAUSE_MONITORING = "com.android.imeisettings.PAUSE_MONITORING"
        val blockedCount = AtomicInteger(0)
        val analyzedCount = AtomicInteger(0)

        private const val SMS_SCAN_INTERVAL_MS = 300_000L // 5 minutes
        private const val SILENT_CALL_MAX_DURATION_SEC = 3
        private const val SILENT_CALL_CHECK_DELAY_MS = 5000L

        // Suspicious SMS patterns (Type-0, binary, USSD-like, premium, phishing)
        // NOTE: Short numbers (3-5 digits) are normal carrier service numbers (e.g. 1020, 900)
        private val SUSPICIOUS_SMS_SENDERS = listOf(
            Regex("^\\+?0{5,}$"),           // Zero-padded numbers (often spoofed)
            Regex("^\\+?\\d{15,}$"),        // Abnormally long numbers (>14 digits)
            Regex("^[A-Za-z]{20,}$"),        // Very long alpha-only sender (spoofed alphanumeric)
            Regex("^\\+?00\\d{10,}$"),       // Double-zero prefix (international spoofing)
            Regex("^[!@#\$%^&*]+$"),         // Special character-only sender IDs
        )
        private val SUSPICIOUS_SMS_BODY = listOf(
            Regex("AT\\+C[A-Z]{3}", RegexOption.IGNORE_CASE),              // AT commands in SMS
            Regex("\\x00{4,}"),                                             // Binary null payloads
            Regex("SIM Toolkit|STK.*command", RegexOption.IGNORE_CASE),     // STK injection
            Regex("OTA.*update.*profile", RegexOption.IGNORE_CASE),         // OTA provisioning
            Regex("ICCID|IMSI|MSISDN", RegexOption.IGNORE_CASE),           // Identity harvesting
            Regex("wappush|service.loading", RegexOption.IGNORE_CASE),      // WAP Push injection
            Regex("USSD.*code|\\*#[0-9*#]{3,}#", RegexOption.IGNORE_CASE), // USSD injection
            Regex("eSIM.*profile|eUICC.*download", RegexOption.IGNORE_CASE),// eSIM manipulation
            Regex("RCS.*provision|jibe.*config", RegexOption.IGNORE_CASE),  // RCS provisioning attack
            Regex("\\\\x[0-9a-f]{2}(\\\\x[0-9a-f]{2}){7,}", RegexOption.IGNORE_CASE), // Hex-encoded binary payload
            Regex("javascript:|<script|onerror=|onload=", RegexOption.IGNORE_CASE), // XSS/injection
            Regex("intent://|content://com\\.", RegexOption.IGNORE_CASE),    // Deep link exploits
            Regex("\\.(apk|dex|sh|exe|msi|bat)([\\s?#]|$)", RegexOption.IGNORE_CASE), // Malware links
            Regex("NAPDEF|PXLOGICAL|BOOTSTRAP", RegexOption.IGNORE_CASE),   // APN provisioning hijack
            Regex("Bearer.*Independent|BIP.*channel", RegexOption.IGNORE_CASE), // BIP channel attacks
        )

        // Known carrier service numbers — whitelisted from suspicious sender detection
        private val CARRIER_WHITELIST = setOf(
            // Ukraine
            "1020", "111", "100", "104", "900", "1011", "5010",
            // Russia
            "900", "9000", "1000", "0500", "0600",
            // Germany
            "2020", "3311", "4636",
            // Poland
            "80085", "8008", "60100",
            // Lithuania / Latvia
            "1661", "1616", "8080",
            // Spain
            "22000", "22001",
            // USA
            "611", "211", "311", "511",
            // Generic
            "0", "10086", "10010"
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var telephonyManager: TelephonyManager

    private var radioLogMonitor: RadioLogMonitor? = null
    private var smsScanJob: Job? = null
    private var smsContentObserver: ContentObserver? = null
    private var callStateCallback: TelephonyCallback? = null
    private var lastCallStartTime = 0L
    private var lastCallNumber: String? = null
    private var silentCallsDetected = AtomicInteger(0)
    private var suspiciousSmsFound = AtomicInteger(0)
    private var lastSmsScanTime = 0L
    private var lastProcessedSmsId = 0L
    private val scannedSmsIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    private var monitoringPaused = false

    private val trans = mapOf(
        "en" to mapOf(
            "analyzed" to "Analyzed %s data packets (Silent SMS, Binary)",
            "blocked" to "Blocked: %s",
            "silent" to "Silent calls: %s",
            "suspicious" to "Suspicious SMS: %s",
            "paused" to "⏸ PAUSED",
            "idle" to "Monitoring: SMS PDU | Calls | Radio Logs"
        ),
        "uk" to mapOf(
            "analyzed" to "Проскановано %s пакетів даних (Silent SMS, Binary)",
            "blocked" to "Заблоковано: %s",
            "silent" to "Приховані дзвінки: %s",
            "suspicious" to "Підозрілі SMS: %s",
            "paused" to "⏸ ПРИЗУПИНЕНО",
            "idle" to "Моніторинг: SMS PDU | Дзвінки | Радіо-логи"
        ),
        "ru" to mapOf(
            "analyzed" to "Проверено %s пакетов данных (Silent SMS, Binary)",
            "blocked" to "Заблокировано: %s",
            "silent" to "Скрытые вызовы: %s",
            "suspicious" to "Подозрительные SMS: %s",
            "paused" to "⏸ ПРИОСТАНОВЛЕНО",
            "idle" to "Мониторинг: SMS PDU | Вызовы | Радио-логи"
        ),
        "de" to mapOf(
            "analyzed" to "Analysierte %s Datenpakete (Silent SMS, Binary)",
            "blocked" to "Blockiert: %s",
            "silent" to "Stille Anrufe: %s",
            "suspicious" to "Verdächtige SMS: %s",
            "paused" to "⏸ PAUSIERT",
            "idle" to "Überwachung: SMS PDU | Anrufe | Funkprotokolle"
        ),
        "pl" to mapOf(
            "analyzed" to "Przeanalizowano %s pakietów danych (Silent SMS, Binary)",
            "blocked" to "Zablokowano: %s",
            "silent" to "Ciche połączenia: %s",
            "suspicious" to "Podejrzane SMS: %s",
            "paused" to "⏸ WSTRZYMANO",
            "idle" to "Monitoring: SMS PDU | Połączenia | Logi radiowe"
        ),
        "lt" to mapOf(
            "analyzed" to "Išanalizuota %s duomenų paketų (Silent SMS, Binary)",
            "blocked" to "Užblokuota: %s",
            "silent" to "Tylūs skambučiai: %s",
            "suspicious" to "Įtartinos SMS: %s",
            "paused" to "⏸ SUSTABDYTA",
            "idle" to "Stebėjimas: SMS PDU | Skambučiai | Radijo žurnalai"
        ),
        "lv" to mapOf(
            "analyzed" to "Analizētas %s datu paketes (Silent SMS, Binary)",
            "blocked" to "Bloķēts: %s",
            "silent" to "Klusie zvani: %s",
            "suspicious" to "Aizdomīgas SMS: %s",
            "paused" to "⏸ PAUZĒTS",
            "idle" to "Monitorings: SMS PDU | Zvani | Radio žurnāli"
        ),
        "es" to mapOf(
            "analyzed" to "Analizados %s paquetes de datos (Silent SMS, Binary)",
            "blocked" to "Bloqueado: %s",
            "silent" to "Llamadas silenciosas: %s",
            "suspicious" to "SMS sospechosos: %s",
            "paused" to "⏸ PAUSADO",
            "idle" to "Monitoreo: SMS PDU | Llamadas | Registros de radio"
        )
    )

    // PDU stats + notification action receiver
    private val pduStatsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PDU_BLOCKED -> {
                    blockedCount.incrementAndGet()
                    updateNotificationWithStats()
                }
                ACTION_PDU_ANALYZED -> {
                    analyzedCount.incrementAndGet()
                    updateNotificationWithStats()
                }
                ACTION_QUICK_ROTATE -> {
                    serviceScope.launch {
                        try {
                            val newImei1 = com.android.imeisettings.util.ImeiGenerator.generateImei()
                            val newImei2 = com.android.imeisettings.util.ImeiGenerator.generateImei()
                            val result = com.android.imeisettings.util.AutoMethodSelector.executeBestMethod(
                                this@PduInterceptorService, newImei1, newImei2
                            )
                            if (result.success) {
                                Log.d(TAG, "Quick IMEI rotation successful")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Quick rotate error: ${e.message}")
                        }
                    }
                }
                ACTION_PAUSE_MONITORING -> {
                    monitoringPaused = !monitoringPaused
                    if (monitoringPaused) {
                        smsScanJob?.cancel()
                        stopRadioLogMonitor()
                    } else {
                        startPeriodicSmsScan()
                        startRadioLogMonitorIfEnabled()
                    }
                    updateNotificationWithStats()
                    Log.d(TAG, "Monitoring ${if (monitoringPaused) "paused" else "resumed"}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification("Initializing PDU Guard...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        db = AppDatabase.getDatabase(this)
        settingsDataStore = SettingsDataStore.getInstance(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Register PDU stats + notification action receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_PDU_BLOCKED)
            addAction(ACTION_PDU_ANALYZED)
            addAction(ACTION_QUICK_ROTATE)
            addAction(ACTION_PAUSE_MONITORING)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pduStatsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pduStatsReceiver, filter)
        }

        // Initialize RILDefender engine (YAML signatures, cell history, SMS whitelist)
        RilDefenderEngine.init(this)
        RilDefenderEngine.loadSmsEventLog(this)

        // Start all monitoring subsystems
        startSilentCallDetection()
        startSmsContentObserver()
        startPeriodicSmsScan()
        startRadioLogMonitorIfEnabled()
        startMmsRcsScanner()

        Log.d(TAG, "PDU Interceptor Service started with full monitoring + RILDefender engine")
        serviceScope.launch {
            db.securityLogDao().insertLog(
                SecurityLog(0, System.currentTimeMillis(), "PDU",
                    "PDU Interceptor started: Silent Call + SMS + MMS/RCS + RadioLog")
            )
        }
        updateNotificationWithStats()
    }

    // ==================== SILENT CALL DETECTION ====================

    @SuppressLint("MissingPermission")
    private fun startSilentCallDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallStateChange(state)
                }
            }
            callStateCallback = callback
            try {
                telephonyManager.registerTelephonyCallback(
                    Executors.newSingleThreadExecutor(), callback
                )
                Log.d(TAG, "Silent call detection registered (TelephonyCallback)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register TelephonyCallback: ${e.message}")
            }
        } else {
            // Fallback: monitor call log periodically
            serviceScope.launch {
                while (isActive) {
                    checkCallLogForSilentCalls()
                    delay(10_000)
                }
            }
        }
    }

    private fun handleCallStateChange(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                lastCallStartTime = System.currentTimeMillis()
                lastCallNumber = null // Cannot get number from TelephonyCallback
                Log.d(TAG, "Incoming call detected, monitoring for silent call...")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (lastCallStartTime == 0L) {
                    lastCallStartTime = System.currentTimeMillis()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastCallStartTime > 0) {
                    val durationMs = System.currentTimeMillis() - lastCallStartTime
                    val durationSec = durationMs / 1000
                    if (durationSec <= SILENT_CALL_MAX_DURATION_SEC && durationMs > 500) {
                        // Very short call — possible silent call / location ping
                        silentCallsDetected.incrementAndGet()
                        val msg = "SILENT CALL DETECTED: Duration=${durationSec}s (threshold=${SILENT_CALL_MAX_DURATION_SEC}s)"
                        Log.w(TAG, "!!! $msg")
                        serviceScope.launch {
                            db.securityLogDao().insertLog(
                                SecurityLog(0, System.currentTimeMillis(), "ALERT", msg)
                            )
                            NetworkStateTracker.forceForensicThreat(25, msg)
                            triggerEmergencyAlert("SILENT CALL", msg)
                        }
                        updateNotificationWithStats()
                    }
                    lastCallStartTime = 0
                    lastCallNumber = null
                }

                // Also check call log for additional silent calls
                serviceScope.launch {
                    delay(SILENT_CALL_CHECK_DELAY_MS)
                    checkCallLogForSilentCalls()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCallLogForSilentCalls() {
        try {
            val cutoff = System.currentTimeMillis() - 60_000 // Last minute
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.TYPE} = ?",
                arrayOf(cutoff.toString(), CallLog.Calls.INCOMING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(0) ?: "Unknown"
                    val duration = it.getLong(1)
                    if (duration <= SILENT_CALL_MAX_DURATION_SEC && duration >= 0) {
                        val isHiddenNumber = number.isBlank() || number == "Unknown" ||
                                number == "-1" || number == "Restricted"
                        if (isHiddenNumber || duration == 0L) {
                            val msg = "Silent call from $number (duration: ${duration}s)"
                            Log.w(TAG, "Call log silent call: $msg")
                            serviceScope.launch {
                                db.securityLogDao().insertLog(
                                    SecurityLog(0, System.currentTimeMillis(), "ALERT", msg)
                                )
                            }
                            silentCallsDetected.incrementAndGet()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call log check error: ${e.message}")
        }
    }

    // ==================== SMS DATABASE SCANNING ====================

    private var observerHandlerThread: android.os.HandlerThread? = null

    private fun startSmsContentObserver() {
        observerHandlerThread = android.os.HandlerThread("SmsObserverThread").also { it.start() }
        smsContentObserver = object : ContentObserver(Handler(observerHandlerThread!!.looper)) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                serviceScope.launch {
                    scanNewSmsMessages()
                }
            }
        }
        try {
            contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI, true, smsContentObserver!!
            )
            Log.d(TAG, "SMS Content Observer registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SMS observer: ${e.message}")
        }
    }

    private fun startPeriodicSmsScan() {
        smsScanJob?.cancel()
        smsScanJob = serviceScope.launch {
            // Initial full scan
            delay(10_000)
            performFullSmsScan()

            // Periodic scans
            while (isActive) {
                delay(SMS_SCAN_INTERVAL_MS)
                performFullSmsScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanNewSmsMessages() {
        try {
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.PROTOCOL),
                "${Telephony.Sms._ID} > ?",
                arrayOf(lastProcessedSmsId.toString()),
                "${Telephony.Sms._ID} DESC LIMIT 10"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    if (id in scannedSmsIds) continue
                    val address = it.getString(1) ?: ""
                    val body = it.getString(2) ?: ""
                    val date = it.getLong(3)
                    val protocol = it.getInt(4)

                    if (id > lastProcessedSmsId) lastProcessedSmsId = id
                    scannedSmsIds.add(id)

                    analyzeSmsForThreats(address, body, date, protocol)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS scan error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun performFullSmsScan() {
        try {
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L // Last 24 hours
            var threatsFound = 0
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.PROTOCOL),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(cutoff.toString()),
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    if (id in scannedSmsIds) continue
                    val address = it.getString(1) ?: ""
                    val body = it.getString(2) ?: ""
                    val date = it.getLong(3)
                    val protocol = it.getInt(4)

                    if (id > lastProcessedSmsId) lastProcessedSmsId = id
                    scannedSmsIds.add(id)

                    if (analyzeSmsForThreats(address, body, date, protocol)) {
                        threatsFound++
                    }
                }
            }
            if (threatsFound > 0) {
                Log.w(TAG, "Periodic SMS scan found $threatsFound suspicious messages")
            }
            // Limit set size to prevent memory leak
            if (scannedSmsIds.size > 5000) {
                val sorted = scannedSmsIds.sorted()
                scannedSmsIds.clear()
                scannedSmsIds.addAll(sorted.takeLast(2000))
            }
            lastSmsScanTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Full SMS scan error: ${e.message}")
        }
    }

    private suspend fun analyzeSmsForThreats(
        sender: String, body: String, date: Long, protocol: Int
    ): Boolean {
        // Check for Type-0 (protocol = 0x40)
        if (protocol == 0x40 || protocol == 64) {
            val msg = "Type-0 Silent SMS detected in database from $sender"
            logThreat(msg, 100)
            return true
        }

        // Check sender patterns (skip whitelisted carrier numbers)
        val cleanSender = sender.removePrefix("+")
        if (cleanSender !in CARRIER_WHITELIST) {
            for (pattern in SUSPICIOUS_SMS_SENDERS) {
                if (pattern.matches(sender)) {
                    val msg = "Suspicious sender pattern: $sender (matched: ${pattern.pattern})"
                    logThreat(msg, 70)
                    return true
                }
            }
        }

        // Check body patterns
        for (pattern in SUSPICIOUS_SMS_BODY) {
            if (pattern.containsMatchIn(body)) {
                val msg = "Suspicious SMS content from $sender: matched '${pattern.pattern}'"
                logThreat(msg, 80)
                return true
            }
        }

        // Check for SMS-STATUS-REPORT abuse (protocol 0x02)
        if (protocol == 0x02 || protocol == 2) {
            val msg = "SMS-STATUS-REPORT from $sender — possible delivery receipt tracking/timing attack"
            logThreat(msg, 65)
            return true
        }

        // Check for empty body with non-zero protocol (binary SMS)
        if (body.isEmpty() && protocol != 0) {
            val msg = "Binary SMS with empty body from $sender (protocol: 0x${"%02x".format(protocol)})"
            logThreat(msg, 75)
            return true
        }

        // Deep body content analysis — URLs, phishing, encoded payloads
        if (body.isNotEmpty()) {
            val lower = body.lowercase()

            // Phishing URL with IP address
            if (Regex("https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(body)) {
                logThreat("SMS from $sender contains IP-based URL (phishing risk)", 60)
                return true
            }
            // Disposable/free TLD
            if (Regex("https?://[^/]+\\.(tk|ml|ga|cf|gq|xyz|top|buzz|click|loan|work|date|racing|win|bid)/",
                    RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                logThreat("SMS from $sender contains disposable TLD link (phishing)", 70)
                return true
            }
            // URL to executable file
            if (Regex("https?://[^\\s]+\\.(apk|dex|exe|bat|sh|bin)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                logThreat("SMS from $sender contains link to executable file (malware)", 90)
                return true
            }
            // Long hex-encoded data (binary command disguised as text)
            if (Regex("[0-9a-fA-F]{40,}").containsMatchIn(body) && body.length < 200) {
                logThreat("SMS from $sender contains hex-encoded payload (possible binary command)", 65)
                return true
            }
            // Credential harvesting keywords from short numbers
            if (sender.length <= 6 && !sender.startsWith("+")) {
                if (lower.contains("пароль") || lower.contains("password") || lower.contains("cvv") ||
                    lower.contains("card number") || lower.contains("номер карти") || lower.contains("номер карты")) {
                    logThreat("SMS from short number $sender requesting credentials (phishing)", 70)
                    return true
                }
            }
        }

        return false
    }

    private suspend fun logThreat(msg: String, severity: Int) {
        Log.w(TAG, "SMS THREAT: $msg")
        suspiciousSmsFound.incrementAndGet()
        db.securityLogDao().insertLog(
            SecurityLog(0, System.currentTimeMillis(), "SMS_SCAN", msg)
        )
        val normalizedSeverity = (severity / 3).coerceIn(5, 35)
        NetworkStateTracker.forceForensicThreat(normalizedSeverity, msg)
        if (severity >= 80) {
            triggerEmergencyAlert("SMS THREAT", msg)
        }
        updateNotificationWithStats()
    }

    // ==================== RADIO LOG MONITOR INTEGRATION ====================

    private fun startRadioLogMonitorIfEnabled() {
        serviceScope.launch {
            try {
                val radioEnabled = settingsDataStore.radioLogMonitorEnabled.first()
                if (radioEnabled) {
                    startRadioLogMonitor()
                }
                // Periodically check if setting changed
                while (isActive) {
                    delay(30_000)
                    val enabled = settingsDataStore.radioLogMonitorEnabled.first()
                    if (enabled && radioLogMonitor == null) {
                        startRadioLogMonitor()
                    } else if (!enabled && radioLogMonitor != null) {
                        stopRadioLogMonitor()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RadioLogMonitor management error: ${e.message}")
            }
        }
    }

    private fun startRadioLogMonitor() {
        if (radioLogMonitor == null) {
            radioLogMonitor = RadioLogMonitor(this)
        }
        radioLogMonitor?.start()
        Log.d(TAG, "RadioLogMonitor started from PduInterceptorService")
        serviceScope.launch {
            db.securityLogDao().insertLog(
                SecurityLog(0, System.currentTimeMillis(), "PDU", "RadioLogMonitor activated for deep modem analysis")
            )
        }
    }

    private fun stopRadioLogMonitor() {
        radioLogMonitor?.stop()
        radioLogMonitor = null
        Log.d(TAG, "RadioLogMonitor stopped")
    }

    // ==================== NOTIFICATIONS & ALERTS ====================

    private fun updateNotificationWithStats() {
        val blocked = blockedCount.get()
        val analyzed = analyzedCount.get()
        val silent = silentCallsDetected.get()
        val suspicious = suspiciousSmsFound.get()

        serviceScope.launch {
            val lang = settingsDataStore.selectedLanguage.first()
            val s = trans[lang] ?: trans["en"]!!

            val parts = mutableListOf<String>()
            if (monitoringPaused) parts.add(s["paused"] ?: "⏸ PAUSED")
            if (analyzed > 0) parts.add((s["analyzed"] ?: "Analyzed %s packets").replace("%s", analyzed.toString()))
            if (blocked > 0) parts.add((s["blocked"] ?: "Blocked: %s").replace("%s", blocked.toString()))
            if (silent > 0) parts.add((s["silent"] ?: "Silent calls: %s").replace("%s", silent.toString()))
            if (suspicious > 0) parts.add((s["suspicious"] ?: "Suspicious SMS: %s").replace("%s", suspicious.toString()))

            val text = if (parts.isEmpty()) {
                s["idle"] ?: "Monitoring: SMS PDU | Calls | Radio Logs"
            } else {
                parts.joinToString(" | ")
            }

            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, createNotification(text))
            } catch (e: Exception) {
                Log.e(TAG, "Notification update error: ${e.message}")
            }
        }
    }

    private fun triggerEmergencyAlert(title: String, reason: String) {
        try {
            val alertIntent = Intent(this, EmergencyOverlayService::class.java).apply {
                putExtra(EmergencyOverlayService.EXTRA_TITLE, title)
                putExtra(EmergencyOverlayService.EXTRA_REASON, reason)
            }
            startForegroundService(alertIntent)
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Cannot start EmergencyOverlay from BG: ${e.message}")
        } catch (e: Exception) {
            try { startService(Intent(this, EmergencyOverlayService::class.java).apply {
                putExtra(EmergencyOverlayService.EXTRA_TITLE, title)
                putExtra(EmergencyOverlayService.EXTRA_REASON, reason)
            }) } catch (e2: Exception) {
                Log.e(TAG, "Emergency alert failed: ${e2.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PDU Interceptor & Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors SMS PDU, silent calls, and radio anomalies"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDU Guard Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_dot)
            .setColor(Color.BLUE)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    // ==================== MMS/RCS SCANNER ====================

    private var lastMmsRcsScanTime = 0L

    private fun startMmsRcsScanner() {
        serviceScope.launch {
            lastMmsRcsScanTime = System.currentTimeMillis()
            while (isActive) {
                delay(60_000) // Scan every 60 seconds
                try {
                    val results = com.android.imeisettings.util.RcsAnalyzer.scanRecentMessages(
                        contentResolver, lastMmsRcsScanTime
                    )
                    for (result in results) {
                        Log.w(TAG, "MMS/RCS threat: ${result.attacks.joinToString()} — ${result.description}")
                        db.securityLogDao().insertLog(
                            SecurityLog(0, System.currentTimeMillis(), "ALERT",
                                "MMS/RCS: ${result.description}")
                        )
                        NetworkStateTracker.forceForensicThreat(
                            when (result.threatLevel) {
                                com.android.imeisettings.util.PduAnalyzer.ThreatLevel.CRITICAL -> 40
                                com.android.imeisettings.util.PduAnalyzer.ThreatLevel.HIGH -> 30
                                com.android.imeisettings.util.PduAnalyzer.ThreatLevel.MEDIUM -> 20
                                else -> 10
                            },
                            "MMS/RCS THREAT: ${result.description}"
                        )
                    }
                    lastMmsRcsScanTime = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.e(TAG, "MMS/RCS scan error: ${e.message}")
                }
            }
        }
    }

    // ==================== LIFECYCLE ====================

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "PduInterceptorService stopping...")
        // Save RILDefender SMS event log on shutdown
        RilDefenderEngine.saveSmsEventLog(this)
        stopRadioLogMonitor()
        try { unregisterReceiver(pduStatsReceiver) } catch (_: Exception) {}
        try {
            smsContentObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) {}
        try {
            observerHandlerThread?.quitSafely()
            observerHandlerThread = null
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateCallback?.let {
                try { telephonyManager.unregisterTelephonyCallback(it) } catch (_: Exception) {}
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
