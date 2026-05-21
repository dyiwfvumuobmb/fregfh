package com.android.imeisettings.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.SecurityLog
import com.android.imeisettings.data.repository.NetworkStateTracker
import com.android.imeisettings.service.EmergencyOverlayService
import com.android.imeisettings.service.PduInterceptorService
import com.android.imeisettings.service.RilDefenderEngine
import com.android.imeisettings.util.MmsAnalyzer
import com.android.imeisettings.util.PduAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PduReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "PDU_INTERCEPTOR"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Intercepted action: $action")

        when (action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                handleSms(context, intent)
            }
            Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION -> {
                handleWapPush(context, intent)
            }
        }
    }

    private fun handleSms(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val signalDbm = getCurrentSignalDbm(context)

        messages.forEach { sms ->
            val result = PduAnalyzer.analyze(sms, signalDbm)

            // ═══ RILDefender analysis (NDSS 2023 engine) ═══
            val pduBytes = sms.pdu ?: byteArrayOf()
            val isTypeZero = sms.protocolIdentifier == 0x40 ||
                (sms.messageClass == android.telephony.SmsMessage.MessageClass.CLASS_0 && sms.messageBody.isNullOrEmpty())
            val isClassZero = sms.messageClass == android.telephony.SmsMessage.MessageClass.CLASS_0
            val isUsimDownload = sms.protocolIdentifier == 0x7F || sms.protocolIdentifier == 0x7C
            val rilVerdict = RilDefenderEngine.analyzeSms(
                pdu = pduBytes,
                isTypeZero = isTypeZero,
                isClassZero = isClassZero,
                isUsimDataDownload = isUsimDownload,
                originAddress = sms.originatingAddress,
                messageBody = sms.messageBody,
                protocolId = sms.protocolIdentifier,
                dataCodingScheme = result.dcs
            )
            if (rilVerdict.shouldBlock && !result.isThreat) {
                // RILDefender caught something PduAnalyzer missed
                Log.w(TAG, "RILDefender BLOCK: ${rilVerdict.description}")
                abortBroadcast()
                NetworkStateTracker.forceForensicThreat(85, "RILDefender: ${rilVerdict.description}")
                triggerAlert(context, "RILDefender [${rilVerdict.type}]", rilVerdict.description)
                saveToLog(context, "RIL_DEF", rilVerdict.description, pduBytes.joinToString("") { "%02X".format(it) })
                notifyPduBlocked(context)
                RilDefenderEngine.logSmsEvent(rilVerdict.type, rilVerdict.source, null, sms.messageBody, pduBytes, rilVerdict.alertLevel)
                return@forEach
            } else if (rilVerdict.shouldNotify && !rilVerdict.shouldBlock) {
                // Notify only — log but don't block
                Log.i(TAG, "RILDefender NOTIFY: ${rilVerdict.description}")
                saveToLog(context, "RIL_NOTIFY", rilVerdict.description, pduBytes.joinToString("") { "%02X".format(it) })
                RilDefenderEngine.logSmsEvent(rilVerdict.type, rilVerdict.source, null, sms.messageBody, pduBytes, rilVerdict.alertLevel)
            }
            // ═══ End RILDefender analysis ═══

            if (result.isThreat) {
                val pduDetails = "PID:0x${"%02x".format(result.protocolId)} | DCS:0x${"%02x".format(result.dcs)} | SC:${result.sca}"
                val allAttacks = result.detectedAttacks.joinToString(", ")
                Log.w(TAG, "!!! THREAT [${result.threatLevel}] BLOCKED: $allAttacks from ${result.sender} ($pduDetails)")

                abortBroadcast()

                val fbsWarning = if (result.isFbsSuspect) " [FBS SUSPECT]" else ""
                val portsInfo = result.udhPorts?.let { " [Port: ${it.destPort}->${it.srcPort}]" } ?: ""
                val fullDescription = "${result.description} ($pduDetails)$fbsWarning$portsInfo"
                val severity = when (result.threatLevel) {
                    PduAnalyzer.ThreatLevel.CRITICAL -> 100
                    PduAnalyzer.ThreatLevel.HIGH -> 90
                    PduAnalyzer.ThreatLevel.MEDIUM -> 75
                    else -> 60
                }
                NetworkStateTracker.forceForensicThreat(severity, fullDescription)
                triggerAlert(context, "FIREWALL [${result.threatLevel}]: $allAttacks", fullDescription)
                saveToLog(context, "ALERT", "Blocked $allAttacks | Level: ${result.threatLevel} | Sender: ${result.sender} | $pduDetails$fbsWarning$portsInfo", result.pduHex)
                notifyPduBlocked(context)

                if (result.honeypotTriggered) {
                    Log.i(TAG, "Honeypot: fake delivery report sent for ${result.sender}")
                }
            } else {
                Log.d(TAG, "SMS Logged: ${result.sender}")
                val pduDetails = "PID:0x${"%02x".format(result.protocolId)} | DCS:0x${"%02x".format(result.dcs)}"
                saveToLog(context, "SMS_IN", "From: ${result.sender} | $pduDetails | Text: ${result.body}", result.pduHex)
                notifyPduAnalyzed(context)
            }
        }

        // Also analyze raw PDUs for attacks that SmsMessage.createFromPdu might miss
        // Only log new threats not already caught by the SmsMessage-based analysis above
        @Suppress("DEPRECATION")
        val rawPdus = intent.extras?.get("pdus") as? Array<*>
        val alreadyDetectedAttacks = messages.mapNotNull { sms ->
            PduAnalyzer.analyze(sms, signalDbm).takeIf { it.isThreat }?.detectedAttacks
        }.flatten().toSet()
        rawPdus?.filterIsInstance<ByteArray>()?.forEach { rawPdu ->
            val rawResult = PduAnalyzer.analyzeRawPdu(rawPdu, signalDbm)
            if (rawResult.isThreat && !rawResult.threatType.isNullOrEmpty()) {
                val newAttacks = rawResult.detectedAttacks.filter { it !in alreadyDetectedAttacks }
                if (newAttacks.isNotEmpty()) {
                    Log.w(TAG, "Raw PDU additional threats: ${newAttacks.joinToString()} - ${rawResult.description}")
                    saveToLog(context, "RAW_PDU", "Additional: ${newAttacks.joinToString()} | ${rawResult.description}", rawResult.pduHex)
                }
            }
        }
    }

    private fun handleWapPush(context: Context, intent: Intent) {
        val data = intent.getByteArrayExtra("data")
        val decoded = PduAnalyzer.decodeWapPush(data)
        val pduHex = data?.joinToString("") { "%02x".format(it) } ?: "null"

        val isSuspicious = decoded.contains("Service Loading") ||
                decoded.contains("THREAT") ||
                decoded.contains("Confirmed Push")

        // Also analyze as MMS PDU if applicable
        if (data != null && data.size > 3) {
            val mmsResult = MmsAnalyzer.analyze(data)
            if (mmsResult.isThreat) {
                Log.w(TAG, "MMS threat detected: ${mmsResult.attacks.joinToString()}")
                saveToLog(context, "ALERT", "MMS ${mmsResult.messageType}: ${mmsResult.description}", pduHex)
                NetworkStateTracker.forceForensicThreat(
                    if (mmsResult.threatLevel == PduAnalyzer.ThreatLevel.CRITICAL) 100 else 80,
                    "MMS THREAT: ${mmsResult.description}"
                )
            }
        }

        Log.w(TAG, "!!! WAP PUSH INTERCEPTED !!! Content: $decoded")
        abortBroadcast()

        val severity = if (isSuspicious) 100 else 75
        val threatMsg = if (isSuspicious) {
            "CRITICAL: Suspicious WAP Push blocked (potential malware delivery). $decoded"
        } else {
            "WAP Push blocked: $decoded"
        }

        NetworkStateTracker.forceForensicThreat(severity, threatMsg)
        triggerAlert(context, "WAP PUSH BLOCKED", threatMsg)
        saveToLog(context, "ALERT", "Blocked WAP PUSH | Content: $decoded | PDU: $pduHex")
    }

    private fun getCurrentSignalDbm(context: Context): Int? {
        return try {
            val sim1State = NetworkStateTracker.networkDetailsSim1.value
            if (sim1State.signalDbm != -120) sim1State.signalDbm else null
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToLog(context: Context, type: String, msg: String, pdu: String? = null) {
        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                db.securityLogDao().insertLog(
                    SecurityLog(
                        timestamp = System.currentTimeMillis(),
                        type = type,
                        message = msg,
                        pdu = pdu
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Database log failed", e)
            }
        }
    }

    private fun notifyPduBlocked(context: Context) {
        try {
            context.sendBroadcast(Intent(PduInterceptorService.ACTION_PDU_BLOCKED).setPackage(context.packageName))
        } catch (_: Exception) {}
    }

    private fun notifyPduAnalyzed(context: Context) {
        try {
            context.sendBroadcast(Intent(PduInterceptorService.ACTION_PDU_ANALYZED).setPackage(context.packageName))
        } catch (_: Exception) {}
    }

    private fun triggerAlert(context: Context, title: String, reason: String) {
        val alertIntent = Intent(context, EmergencyOverlayService::class.java).apply {
            putExtra(EmergencyOverlayService.EXTRA_TITLE, title)
            putExtra(EmergencyOverlayService.EXTRA_REASON, reason)
        }
        try {
            ContextCompat.startForegroundService(context, alertIntent)
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 14+: FGS start not allowed from background context
            Log.w(TAG, "Cannot start EmergencyOverlay from BG: ${e.message}")
            // Fallback: show high-priority notification instead
            showEmergencyNotification(context, title, reason)
        } catch (e: Exception) {
            try {
                context.startService(alertIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Emergency alert fallback failed: ${e2.message}")
                showEmergencyNotification(context, title, reason)
            }
        }
    }

    private fun showEmergencyNotification(context: Context, title: String, reason: String) {
        try {
            val channelId = "pdu_emergency_fallback"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "Security Alert",
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                context.getSystemService(android.app.NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
            val intent = Intent(context, com.android.imeisettings.MainActivity::class.java)
            val pi = android.app.PendingIntent.getActivity(context, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(reason)
                .setSmallIcon(com.android.imeisettings.R.drawable.ic_notification_dot)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            context.getSystemService(android.app.NotificationManager::class.java)
                .notify(9999, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Emergency notification fallback failed: ${e.message}")
        }
    }
}
