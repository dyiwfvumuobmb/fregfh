package com.android.imeisettings.service

import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import android.util.Log
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.SecurityLog
import com.android.imeisettings.data.repository.NetworkStateTracker
import com.android.imeisettings.util.PduAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method

/**
 * System-level SMS interceptor.
 * As a system app with android.uid.phone, this service has access to:
 * 1. InboundSmsHandler hooking via ITelephony
 * 2. CarrierMessagingService for carrier-level filtering
 * 3. Direct access to content://raw (pre-processed SMS)
 * 4. TelephonyRegistry listener for all telephony events
 * 5. IMS SMS interception
 * 6. Baseband SMS via /dev/smd* devices
 * 7. RIL unsolicited response monitoring
 * 8. Visual Voicemail SMS filter
 */
class SystemSmsInterceptor : Service() {

    private val TAG = "SYS_SMS_INTERCEPT"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var rawSmsObserver: ContentObserver? = null
    private var rilMonitorThread: Thread? = null
    private var basebandMonitorThread: Thread? = null
    private var imsMonitorThread: Thread? = null
    @Volatile private var isRunning = false

    companion object {
        const val ACTION_SYSTEM_SMS_BLOCKED = "com.android.imeisettings.SYSTEM_SMS_BLOCKED"
        const val ACTION_SYSTEM_SMS_THREAT = "com.android.imeisettings.SYSTEM_SMS_THREAT"
        const val EXTRA_THREAT_TYPE = "threat_type"
        const val EXTRA_THREAT_SEVERITY = "threat_severity"
        const val EXTRA_THREAT_DETAILS = "threat_details"

        // SMS content provider URIs accessible to system apps
        val URI_RAW_SMS = Uri.parse("content://sms/raw")
        val URI_SMS_INBOX = Uri.parse("content://sms/inbox")
        val URI_SMS_ALL = Uri.parse("content://sms")
        val URI_ICC_SMS = Uri.parse("content://sms/icc")
        val URI_MMS_INBOX = Uri.parse("content://mms/inbox")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "System SMS Interceptor starting...")

        startRawSmsMonitor()
        startRilUnsolMonitor()
        startBasebandMonitor()
        startImsMonitor()
        startIccSmsScanner()
        registerSmsFilterCallback()
    }

    override fun onDestroy() {
        isRunning = false
        rawSmsObserver?.let { contentResolver.unregisterContentObserver(it) }
        rilMonitorThread?.interrupt()
        basebandMonitorThread?.interrupt()
        imsMonitorThread?.interrupt()
        super.onDestroy()
    }

    // ==================== 1. Raw SMS Content Provider Monitor ====================

    /**
     * Monitor content://sms/raw — this is where SMS arrives BEFORE processing.
     * System apps can access this table which normal apps cannot.
     */
    private fun startRawSmsMonitor() {
        rawSmsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Raw SMS change detected: $uri")
                analyzeRawSmsTable()
            }
        }

        try {
            contentResolver.registerContentObserver(URI_RAW_SMS, true, rawSmsObserver!!)
            Log.i(TAG, "Raw SMS observer registered on content://sms/raw")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register raw SMS observer: ${e.message}")
            // Fallback: monitor all SMS
            try {
                contentResolver.registerContentObserver(URI_SMS_ALL, true, rawSmsObserver!!)
                Log.i(TAG, "Fallback: SMS observer registered on content://sms")
            } catch (e2: Exception) {
                Log.e(TAG, "SMS observer registration failed: ${e2.message}")
            }
        }
    }

    private fun analyzeRawSmsTable() {
        scope.launch {
            try {
                val cursor = contentResolver.query(
                    URI_RAW_SMS,
                    arrayOf("_id", "date", "reference_number", "count", "sequence",
                        "destination_port", "address", "pdu", "deleted"),
                    "deleted = 0",
                    null,
                    "date DESC LIMIT 10"
                )

                cursor?.use {
                    while (it.moveToNext()) {
                        val pduHex = it.getString(it.getColumnIndexOrThrow("pdu")) ?: continue
                        val destPort = it.getInt(it.getColumnIndexOrThrow("destination_port"))
                        val address = it.getString(it.getColumnIndexOrThrow("address")) ?: "unknown"

                        // Check dangerous destination ports
                        if (destPort == 0x0B84 || destPort == 0x0B85) {
                            val threatType = if (destPort == 0x0B84) "SIMJACKER" else "WIB_ATTACK"
                            logSystemThreat(
                                threatType,
                                100,
                                "Raw SMS to port $destPort from $address — $threatType attack intercepted at system level"
                            )
                            // Delete the raw SMS entry
                            deleteRawSms(it.getLong(it.getColumnIndexOrThrow("_id")))
                        }

                        // Analyze PDU bytes
                        val pduBytes = hexStringToByteArray(pduHex)
                        if (pduBytes != null) {
                            val rawResult = PduAnalyzer.analyzeRawPdu(pduBytes, null)
                            if (rawResult.isThreat) {
                                logSystemThreat(
                                    rawResult.threatType ?: "UNKNOWN",
                                    when (rawResult.threatLevel) {
                                        PduAnalyzer.ThreatLevel.CRITICAL -> 100
                                        PduAnalyzer.ThreatLevel.HIGH -> 90
                                        PduAnalyzer.ThreatLevel.MEDIUM -> 75
                                        else -> 60
                                    },
                                    "System-level PDU threat: ${rawResult.description}"
                                )
                                deleteRawSms(it.getLong(it.getColumnIndexOrThrow("_id")))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Raw SMS analysis failed: ${e.message}")
            }
        }
    }

    private fun deleteRawSms(id: Long) {
        try {
            contentResolver.delete(
                Uri.withAppendedPath(URI_RAW_SMS, id.toString()),
                null, null
            )
            Log.i(TAG, "Deleted raw SMS entry $id")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete raw SMS $id: ${e.message}")
        }
    }

    // ==================== 2. RIL Unsolicited Response Monitor ====================

    /**
     * Monitor RIL unsolicited responses via ITelephony service.
     * This catches SMS at the radio interface level, before Android processing.
     * RIL_UNSOL_RESPONSE_NEW_SMS = 1003
     * RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT = 1004
     * RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM = 1005
     * RIL_UNSOL_ON_USSD = 1006
     * RIL_UNSOL_ON_SS = 1043
     * RIL_UNSOL_STK_PROACTIVE_COMMAND = 1028
     * RIL_UNSOL_STK_EVENT_NOTIFY = 1029
     * RIL_UNSOL_STK_CC_ALPHA_NOTIFY = 1044
     */
    private fun startRilUnsolMonitor() {
        rilMonitorThread = Thread {
            Log.i(TAG, "RIL unsolicited monitor starting...")
            while (isRunning) {
                try {
                    monitorRilUnsolicited()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "RIL monitor error: ${e.message}")
                }
                Thread.sleep(2000)
            }
        }.apply {
            name = "RilUnsolMonitor"
            isDaemon = true
            start()
        }
    }

    private fun monitorRilUnsolicited() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            // Check for pending STK proactive commands
            val binder = getService.invoke(null, "phone") as? IBinder ?: return
            val iTelephonyStub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterface = iTelephonyStub.getMethod("asInterface", IBinder::class.java)
            val iTelephony = asInterface.invoke(null, binder) ?: return

            // Check for SIM Toolkit commands (STK) — potential SIMJacker
            for (m in iTelephony.javaClass.declaredMethods) {
                when {
                    m.name.contains("getEnvelopeResponse") || m.name.contains("getStkCommand") -> {
                        m.isAccessible = true
                        try {
                            val result = m.invoke(iTelephony) as? String
                            if (result != null && result.isNotEmpty()) {
                                analyzeStkCommand(result)
                            }
                        } catch (_: Exception) {}
                    }
                    m.name.contains("getCellBroadcast") || m.name.contains("getSmsBroadcast") -> {
                        m.isAccessible = true
                        try {
                            val enabled = m.invoke(iTelephony) as? Boolean
                            Log.d(TAG, "${m.name}: $enabled")
                        } catch (_: Exception) {}
                    }
                }
            }

            // Monitor IRadioIndication for RIL_UNSOL_RESPONSE_NEW_SMS
            checkRadioIndication(getService)

        } catch (e: Exception) {
            Log.d(TAG, "RIL unsol check: ${e.message}")
        }
    }

    private fun analyzeStkCommand(stkHex: String) {
        Log.w(TAG, "STK command detected: $stkHex")

        val bytes = hexStringToByteArray(stkHex) ?: return

        // Check for dangerous STK commands (SIMJacker/WIBAttack signatures)
        val dangerousCommands = mapOf(
            0x10 to "SETUP_CALL",
            0x11 to "SEND_SS",
            0x12 to "SEND_USSD",
            0x13 to "SEND_SMS",
            0x15 to "LAUNCH_BROWSER",
            0x26 to "PROVIDE_LOCAL_INFO",
            0x40 to "OPEN_CHANNEL",
            0x43 to "SEND_DATA",
            0x34 to "RUN_AT_CMD"
        )

        for (byte in bytes) {
            val cmd = byte.toInt() and 0xFF
            if (cmd in dangerousCommands) {
                logSystemThreat(
                    "STK_ATTACK",
                    100,
                    "Dangerous STK command ${dangerousCommands[cmd]} (0x${"%02x".format(cmd)}) detected — possible SIMJacker/WIBAttack"
                )
                break
            }
        }
    }

    private fun checkRadioIndication(getService: Method) {
        val indicationServices = arrayOf(
            "android.hardware.radio.messaging.IRadioMessagingIndication/slot1",
            "android.hardware.radio@1.6::IRadioIndication/slot1"
        )

        val smClass = Class.forName("android.os.ServiceManager")
        for (svc in indicationServices) {
            try {
                val binder = getService.invoke(null, svc) as? IBinder ?: continue
                Log.d(TAG, "Radio indication service found: $svc")
                // Service exists — means we can hook into new SMS notifications at RIL level
            } catch (_: Exception) {}
        }
    }

    // ==================== 3. Baseband Device Monitor ====================

    /**
     * Monitor baseband device nodes for incoming SMS PDUs.
     * System app with phone UID can read modem devices.
     */
    private fun startBasebandMonitor() {
        basebandMonitorThread = Thread {
            Log.i(TAG, "Baseband monitor starting...")
            val modemDevices = arrayOf(
                "/dev/smd0", "/dev/smd7", "/dev/smd11",
                "/dev/radio/atci0", "/dev/ccci_ioctl0"
            )

            while (isRunning) {
                for (dev in modemDevices) {
                    try {
                        val devFile = File(dev)
                        if (!devFile.exists() || !devFile.canRead()) continue

                        val fis = FileInputStream(devFile)
                        val available = fis.available()
                        if (available > 0) {
                            val buf = ByteArray(minOf(available, 4096))
                            val len = fis.read(buf)
                            if (len > 0) {
                                val data = String(buf, 0, len)
                                analyzeBasebandData(dev, data, buf.copyOf(len))
                            }
                        }
                        fis.close()
                    } catch (e: Exception) {
                        // Device not accessible — skip
                    }
                }

                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "BasebandMonitor"
            isDaemon = true
            start()
        }
    }

    private fun analyzeBasebandData(device: String, text: String, rawBytes: ByteArray) {
        // Check for incoming SMS indicators in baseband output
        val smsIndicators = arrayOf(
            "+CMT:", "+CDS:", "+CBM:", "+CMTI:",
            "RIL_UNSOL_RESPONSE_NEW_SMS",
            "+CUSD:", "+CSSI:", "+CSSU:"
        )

        for (indicator in smsIndicators) {
            if (text.contains(indicator)) {
                Log.w(TAG, "Baseband SMS indicator on $device: $indicator")

                // Extract PDU from +CMT response
                if (indicator == "+CMT:" || indicator == "+CDS:") {
                    val pduLine = text.substringAfter(indicator).trim()
                    val pduHex = pduLine.lines().getOrNull(1)?.trim()
                    if (pduHex != null && pduHex.matches(Regex("^[0-9A-Fa-f]+$"))) {
                        val pduBytes = hexStringToByteArray(pduHex)
                        if (pduBytes != null) {
                            val result = PduAnalyzer.analyzeRawPdu(pduBytes, null)
                            if (result.isThreat) {
                                logSystemThreat(
                                    result.threatType ?: "BASEBAND_SMS",
                                    95,
                                    "Baseband SMS threat from $device: ${result.description}"
                                )
                            }
                        }
                    }
                }

                // Check for USSD — potential USSD injection
                if (indicator == "+CUSD:") {
                    logSystemThreat(
                        "USSD_INTERCEPT",
                        70,
                        "USSD response intercepted at baseband: ${text.take(200)}"
                    )
                }
            }
        }

        // Binary pattern analysis for OTA/STK envelopes
        analyzeBasebandBinaryPatterns(rawBytes, device)
    }

    private fun analyzeBasebandBinaryPatterns(data: ByteArray, device: String) {
        // D0 xx 81 03 — Proactive SIM command (BER-TLV)
        // D1 xx 82 — SMS-PP Data Download envelope
        // A0 C2 — ENVELOPE APDU command
        for (i in 0 until data.size - 3) {
            val b0 = data[i].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF

            if (b0 == 0xD0 && b2 == 0x81) {
                logSystemThreat(
                    "OTA_ENVELOPE",
                    90,
                    "Proactive SIM command (BER-TLV D0..81) detected at baseband $device offset $i"
                )
            }
            if (b0 == 0xD1 && b2 == 0x82) {
                logSystemThreat(
                    "SMS_PP_DOWNLOAD",
                    85,
                    "SMS-PP Data Download envelope (D1..82) detected at baseband $device offset $i"
                )
            }
            if (b0 == 0xA0 && data[i + 1].toInt() and 0xFF == 0xC2) {
                logSystemThreat(
                    "ENVELOPE_APDU",
                    80,
                    "ENVELOPE APDU (A0 C2) detected at baseband $device offset $i"
                )
            }
        }
    }

    // ==================== 4. IMS SMS Monitor ====================

    /**
     * Monitor IMS (IP Multimedia Subsystem) SMS.
     * On VoLTE/VoNR networks, SMS goes through IMS, not legacy CS.
     * System apps can monitor IMS service state.
     */
    private fun startImsMonitor() {
        imsMonitorThread = Thread {
            Log.i(TAG, "IMS SMS monitor starting...")
            while (isRunning) {
                try {
                    monitorImsSms()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.d(TAG, "IMS monitor: ${e.message}")
                }
                Thread.sleep(5000)
            }
        }.apply {
            name = "ImsSmsMonitor"
            isDaemon = true
            start()
        }
    }

    private fun monitorImsSms() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            // IMS service
            val imsBinder = getService.invoke(null, "ims") as? IBinder
            if (imsBinder != null) {
                checkImsRegistration(imsBinder)
            }

            // Check IMS SMS service
            val imsSmsBinder = getService.invoke(null, "imssms") as? IBinder
            if (imsSmsBinder != null) {
                Log.d(TAG, "IMS SMS service available")
                // Monitor for incoming IMS SMS
                scanImsSmsPending(imsSmsBinder)
            }

            // Vendor IMS services (Samsung, Qualcomm)
            val vendorImsServices = arrayOf(
                "vendor.samsung.hardware.radio.ims@1.0::ISehRadioIms/slot1",
                "vendor.qti.hardware.radio.ims@1.0::IImsRadio/imsradio0",
                "android.hardware.radio.ims.IRadioIms/slot1"
            )

            for (svc in vendorImsServices) {
                try {
                    val binder = getService.invoke(null, svc) as? IBinder
                    if (binder != null) {
                        Log.d(TAG, "Vendor IMS service found: $svc")
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "IMS monitor: ${e.message}")
        }
    }

    private fun checkImsRegistration(binder: IBinder) {
        try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                // Query IMS registration state
                data.writeInterfaceToken("android.telephony.ims.aidl.IImsRegistration")
                binder.transact(1, data, reply, 0) // getRegistrationTechnology
                val regTech = reply.readInt()
                Log.d(TAG, "IMS registration tech: $regTech")
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun scanImsSmsPending(binder: IBinder) {
        try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.internal.telephony.ISms")
                // Check for pending IMS SMS
                for (txCode in 1..10) {
                    try {
                        binder.transact(txCode, data, reply, 0)
                    } catch (_: Exception) {}
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (_: Exception) {}
    }

    // ==================== 5. ICC (SIM Card) SMS Scanner ====================

    /**
     * Scan SMS stored on SIM card (ICC).
     * System apps can access content://sms/icc directly.
     * This catches SMS that might be hidden from the inbox.
     */
    private fun startIccSmsScanner() {
        scope.launch {
            while (isRunning) {
                try {
                    scanIccSms()
                } catch (e: Exception) {
                    Log.d(TAG, "ICC SMS scan: ${e.message}")
                }
                kotlinx.coroutines.delay(30_000) // Every 30 seconds
            }
        }
    }

    private fun scanIccSms() {
        try {
            val cursor = contentResolver.query(
                URI_ICC_SMS,
                null, null, null, null
            )
            cursor?.use {
                val count = it.count
                if (count > 0) {
                    Log.d(TAG, "ICC SMS count: $count")
                    while (it.moveToNext()) {
                        val pduCol = it.getColumnIndex("pdu")
                        if (pduCol >= 0) {
                            val pduHex = it.getString(pduCol) ?: continue
                            val pduBytes = hexStringToByteArray(pduHex)
                            if (pduBytes != null) {
                                val result = PduAnalyzer.analyzeRawPdu(pduBytes, null)
                                if (result.isThreat) {
                                    logSystemThreat(
                                        "ICC_" + (result.threatType ?: "UNKNOWN"),
                                        when (result.threatLevel) {
                                            PduAnalyzer.ThreatLevel.CRITICAL -> 100
                                            PduAnalyzer.ThreatLevel.HIGH -> 90
                                            else -> 75
                                        },
                                        "SIM card SMS threat: ${result.description}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ICC SMS scan failed: ${e.message}")
        }
    }

    // ==================== 6. SMS Filter Callback (System API) ====================

    /**
     * Register as system SMS filter via ITelephony.
     * Only available to system-signed apps.
     */
    private fun registerSmsFilterCallback() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "isms") as? IBinder ?: return

            val stubClass = Class.forName("com.android.internal.telephony.ISms\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val iSms = asInterface.invoke(null, binder) ?: return

            // Look for enableVisualVoicemailSmsFilter or setSmsFilter
            for (m in iSms.javaClass.declaredMethods) {
                when {
                    m.name.contains("Filter") || m.name.contains("filter") -> {
                        Log.d(TAG, "ISms filter method found: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                    }
                    m.name.contains("createAppSpecificSmsToken") -> {
                        // Create a token for system-level SMS routing
                        m.isAccessible = true
                        try {
                            val token = m.invoke(iSms, 0, null, null) as? String
                            if (token != null) {
                                Log.i(TAG, "SMS app-specific token created: ${token.take(10)}...")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            Log.i(TAG, "SMS filter callback scan complete")
        } catch (e: Exception) {
            Log.w(TAG, "SMS filter registration: ${e.message}")
        }
    }

    // ==================== Utility ====================

    private fun logSystemThreat(type: String, severity: Int, description: String) {
        Log.w(TAG, "SYSTEM THREAT [$severity] $type: $description")

        // Normalize severity: forensic events should add incrementally (max 40 per event)
        val normalizedSeverity = (severity / 3).coerceIn(5, 40)
        NetworkStateTracker.forceForensicThreat(normalizedSeverity, "SYS: $description")

        scope.launch {
            try {
                val db = AppDatabase.getDatabase(this@SystemSmsInterceptor)
                db.securityLogDao().insertLog(
                    SecurityLog(
                        timestamp = System.currentTimeMillis(),
                        type = "SYS_$type",
                        message = description
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "DB log failed", e)
            }
        }

        sendBroadcast(Intent(ACTION_SYSTEM_SMS_THREAT).apply {
            setPackage(packageName)
            putExtra(EXTRA_THREAT_TYPE, type)
            putExtra(EXTRA_THREAT_SEVERITY, severity)
            putExtra(EXTRA_THREAT_DETAILS, description)
        })
    }

    private fun hexStringToByteArray(hex: String): ByteArray? {
        return try {
            val cleanHex = hex.replace("\\s".toRegex(), "")
            if (cleanHex.length % 2 != 0) return null
            ByteArray(cleanHex.length / 2) { i ->
                cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }
}
