package com.android.imeisettings.service

import android.os.Build
import android.service.carrier.CarrierMessagingService
import android.service.carrier.MessagePdu
import android.telephony.SmsMessage
import android.util.Log
import com.android.imeisettings.data.repository.NetworkStateTracker
import com.android.imeisettings.util.PduAnalyzer

/**
 * CarrierMessagingService — carrier-level SMS filter.
 *
 * As a system app signed with platform key, we can register as a carrier messaging
 * service and intercept ALL incoming SMS before they reach any app.
 * This is the deepest level of SMS interception available in Android.
 *
 * Required: system signature + CARRIER_FILTER_SMS_DISPATCHED permission
 *
 * Flow: Modem → RIL → InboundSmsHandler → CarrierMessagingService → SMS apps
 * We intercept between InboundSmsHandler and SMS apps.
 */
class SystemCarrierSmsFilter : CarrierMessagingService() {

    private val TAG = "CARRIER_SMS_FILTER"

    /**
     * Called for every incoming SMS before it reaches any app.
     * We can inspect and optionally DROP the message.
     */
    override fun onReceiveTextSms(
        pdu: MessagePdu,
        format: String,
        destPort: Int,
        subId: Int,
        callback: ResultCallback<Int>
    ) {
        Log.d(TAG, "Carrier SMS filter: format=$format port=$destPort subId=$subId")

        val pdus = pdu.pdus
        var shouldBlock = false
        var threatDesc = ""

        for (rawPdu in pdus) {
            try {
                // Deep PDU analysis
                val rawResult = PduAnalyzer.analyzeRawPdu(rawPdu, null)
                if (rawResult.isThreat) {
                    shouldBlock = true
                    threatDesc += "PDU threat: ${rawResult.description}\n"
                    Log.w(TAG, "Carrier filter THREAT: ${rawResult.threatType} — ${rawResult.description}")
                }

                // Parse as SmsMessage for secondary analysis
                val sms = SmsMessage.createFromPdu(rawPdu, format)
                if (sms != null) {
                    val result = PduAnalyzer.analyze(sms, null)
                    if (result.isThreat) {
                        shouldBlock = true
                        threatDesc += "SMS threat: ${result.description}\n"
                    }

                    // Additional carrier-level checks
                    if (checkCarrierLevelThreat(sms, destPort, rawPdu)) {
                        shouldBlock = true
                        threatDesc += "Carrier-level threat detected\n"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Carrier filter analysis error: ${e.message}")
            }
        }

        // ═══ RILDefender engine analysis (carrier level) ═══
        if (!shouldBlock) {
            for (rawPdu in pdus) {
                try {
                    val sms = SmsMessage.createFromPdu(rawPdu, format)
                    if (sms != null) {
                        val rilVerdict = RilDefenderEngine.analyzeSms(
                            pdu = rawPdu,
                            isTypeZero = sms.protocolIdentifier == 0x40,
                            isClassZero = sms.messageClass == SmsMessage.MessageClass.CLASS_0,
                            isUsimDataDownload = sms.protocolIdentifier in listOf(0x7C, 0x7D, 0x7F),
                            originAddress = sms.originatingAddress,
                            messageBody = sms.messageBody,
                            protocolId = sms.protocolIdentifier,
                            dataCodingScheme = 0
                        )
                        if (rilVerdict.shouldBlock) {
                            shouldBlock = true
                            threatDesc += "RILDefender: ${rilVerdict.description}\n"
                            RilDefenderEngine.logSmsEvent(
                                rilVerdict.type, rilVerdict.source, null,
                                sms.messageBody, rawPdu, rilVerdict.alertLevel
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "RILDefender analysis error: ${e.message}")
                }
            }
        }

        if (shouldBlock) {
            Log.w(TAG, "BLOCKING SMS at carrier level: $threatDesc")
            NetworkStateTracker.forceForensicThreat(95, "Carrier filter: $threatDesc")
            // RECEIVE_OPTIONS_DROP = 1 — DROP the message
            callback.onReceiveResult(RECEIVE_OPTIONS_DROP)
        } else {
            // RECEIVE_OPTIONS_DEFAULT = 0 — ALLOW the message
            callback.onReceiveResult(RECEIVE_OPTIONS_DEFAULT)
        }
    }

    /**
     * Called for incoming MMS messages.
     */
    override fun onDownloadMms(
        contentUri: android.net.Uri,
        subId: Int,
        location: android.net.Uri,
        callback: ResultCallback<Int>
    ) {
        Log.d(TAG, "Carrier MMS download: $contentUri subId=$subId")
        // Allow MMS but log it
        callback.onReceiveResult(DOWNLOAD_STATUS_OK)
    }

    /**
     * Additional carrier-level threat detection.
     * Checks that are only possible at the carrier messaging service level.
     */
    private fun checkCarrierLevelThreat(sms: SmsMessage, destPort: Int, rawPdu: ByteArray): Boolean {
        // Check for Type-0 SMS (silent SMS / ping)
        val protocolId = sms.protocolIdentifier
        if (protocolId == 0x40) {
            Log.w(TAG, "Type-0 SMS detected at carrier level from ${sms.originatingAddress}")
            return true
        }

        // SIM Data Download (PID = 0x7F)
        if (protocolId == 0x7F) {
            Log.w(TAG, "SIM Data Download SMS from ${sms.originatingAddress}")
            return true
        }

        // ME Data Download (PID = 0x7C)
        if (protocolId == 0x7C) {
            Log.w(TAG, "ME Data Download SMS from ${sms.originatingAddress}")
            return true
        }

        // USIM Data Download (PID = 0x7D)
        if (protocolId == 0x7D) {
            Log.w(TAG, "USIM Data Download SMS from ${sms.originatingAddress}")
            return true
        }

        // Class 0 SMS (Flash SMS) — potential screen overlay attack
        val msgClass = sms.messageClass
        if (msgClass == SmsMessage.MessageClass.CLASS_0) {
            Log.w(TAG, "Class-0 Flash SMS from ${sms.originatingAddress}")
            return true
        }

        // Binary SMS to SIMJacker/WIBAttack ports
        if (destPort == 0x0B84 || destPort == 0x0B85) {
            Log.w(TAG, "SMS to SIMJacker port 0x${"%04x".format(destPort)}")
            return true
        }

        // WAP Push to suspicious ports
        if (destPort == 2948 || destPort == 9200 || destPort == 9201) {
            Log.w(TAG, "WAP Push SMS to port $destPort")
            return true
        }

        // Check for OTA/UICC commands in PDU
        return checkOtaCommands(rawPdu)
    }

    /**
     * Check for Over-The-Air (OTA) commands embedded in SMS PDU.
     * These can reprogram the SIM card.
     */
    private fun checkOtaCommands(pdu: ByteArray): Boolean {
        // Security Parameter Indicator (SPI) in OTA SMS
        // Command Packet Structure: CPI + CPL + CHI + CHL + SPI + KIc + KID + TAR
        // If we find TAR (Toolkit Application Reference) patterns:

        for (i in 0 until pdu.size - 6) {
            val b = pdu[i].toInt() and 0xFF

            // OTA Security Header tag = 0x70 or 0x71
            if (b == 0x70 || b == 0x71) {
                // Check for known TAR values
                if (i + 5 < pdu.size) {
                    val tar1 = pdu[i + 3].toInt() and 0xFF
                    val tar2 = pdu[i + 4].toInt() and 0xFF
                    val tar3 = pdu[i + 5].toInt() and 0xFF

                    // S@T Browser TAR = 53 40 54 (S@T)
                    if (tar1 == 0x53 && tar2 == 0x40 && tar3 == 0x54) {
                        Log.w(TAG, "S@T Browser TAR detected — SIMJacker!")
                        return true
                    }
                    // WIB TAR = 57 49 42 (WIB)
                    if (tar1 == 0x57 && tar2 == 0x49 && tar3 == 0x42) {
                        Log.w(TAG, "WIB TAR detected — WIBAttack!")
                        return true
                    }
                    // Generic 000000 TAR (default applet)
                    if (tar1 == 0x00 && tar2 == 0x00 && tar3 == 0x00) {
                        Log.w(TAG, "Default TAR 000000 — potential OTA command")
                        return true
                    }
                }
            }
        }

        return false
    }

    companion object {
        const val DOWNLOAD_STATUS_OK = 0
        const val RECEIVE_OPTIONS_DEFAULT = 0
        const val RECEIVE_OPTIONS_DROP = 1
    }
}
