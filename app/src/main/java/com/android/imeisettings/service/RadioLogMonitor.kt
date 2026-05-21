package com.android.imeisettings.service

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class RadioLogMonitor(private val context: Context) {

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var logcatProcess: Process? = null

    companion object {
        private const val TAG = "CONSUL_RADIO"
        const val ACTION_FORENSIC = "com.android.imeisettings.FORENSIC_EVENT"
        const val PERMISSION_FORENSIC = "com.android.imeisettings.permission.FORENSIC_EVENT"
        
        // Core patterns from existing + AIMSICD SmsDetector logcat scraping
        private val SUSPICIOUS_PATTERNS = mapOf(
            // === Existing radio-level detections ===
            "SILENT_CALL" to Regex("mt_call_id.*state[:\\s]*0|RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND", RegexOption.IGNORE_CASE),
            "SILENT_PING" to Regex("Paging Type 0|Immediate Assignment.*(Dummy|Silent)", RegexOption.IGNORE_CASE),
            "RIL_REJECT_CAUSE" to Regex("reject cause[:\\s]*(\\d+)", RegexOption.IGNORE_CASE),
            "CIPHER_DISABLED" to Regex("Ciphering is OFF|A5/0|encryption disabled", RegexOption.IGNORE_CASE),
            "CELL_RESELECT_LIMIT" to Regex("Cell reselection limit reached", RegexOption.IGNORE_CASE),
            "FAKE_CELL_DETECTED" to Regex("Unexpected Network Name|mismatch MCC/MNC", RegexOption.IGNORE_CASE),
            "RRC_REJECT" to Regex("RRC connection reject|Service Request failed", RegexOption.IGNORE_CASE),

            // === AIMSICD-style SMS detection via logcat (SmsDetector) ===
            "TYPE0_SMS" to Regex("SMS type 0|pid=64|TP-PID:.*64|sms.*class.*0.*type.*0|BroadcastReceiver.*SMS_RECEIVED.*type0", RegexOption.IGNORE_CASE),
            "MWI_SMS" to Regex("MWI.*Message|Message Waiting Indicator|mwi.*sms|Voicemail.*Indicator", RegexOption.IGNORE_CASE),
            "WAP_PUSH_INJECT" to Regex("WapPush.*inject|WAP_PUSH_RECEIVED|wappush.*service.*loading", RegexOption.IGNORE_CASE),
            "BINARY_SMS" to Regex("sms.*binary.*data|port_dst.*2948|port_dst.*2949|BearerData.*msgType.*0x04", RegexOption.IGNORE_CASE),

            // === SnoopSnitch-style deep modem event detections ===
            "IDENTITY_REQUEST" to Regex("IDENTITY REQUEST|IMSI.*DETACH|MM.*IDENTITY.*REQ|LUR.*reject", RegexOption.IGNORE_CASE),
            "AUTH_REJECT" to Regex("AUTH.*REJECT|Authentication failure|AUTHENTICATION_REJECT", RegexOption.IGNORE_CASE),
            "LOCATION_UPDATE_REJECT" to Regex("LU.*REJECT|Location Update Reject|LAU.*rejected|cause.*#(2|3|6|11|12|13|15)", RegexOption.IGNORE_CASE),
            "PAGING_FLOOD" to Regex("Paging.*Paging.*Paging|paging_record_list.*count.*[5-9]|PAGING_IND.*PAGING_IND", RegexOption.IGNORE_CASE),
            "CHANNEL_RELEASE_ABORT" to Regex("CHANNEL RELEASE|RR_ABORT_IND|radio_link_failure|T3240.*expired", RegexOption.IGNORE_CASE),
            "IMSI_ATTACH_ANOMALY" to Regex("ATTACH.*REJECT|COMBINED.*ATTACH.*REJECT|EMM.*cause.*#(3|6|7|8|11|12|13|14|15|25)", RegexOption.IGNORE_CASE),
            "TRACKING_AREA_REJECT" to Regex("TAU.*REJECT|TRACKING AREA UPDATE.*REJECT|TAU.*cause", RegexOption.IGNORE_CASE),
            "NULL_CIPHER_MODE" to Regex("CIPHER_MODE_CMD.*A5/0|cipher_mode_setting.*0x00|GEA0|NEA0|EEA0.*only", RegexOption.IGNORE_CASE),
            "SILENT_SMS_DELIVER" to Regex("SMS-DELIVER.*pid.*0x40|mt_sms.*pid.*64|new_sms.*protocol.*64", RegexOption.IGNORE_CASE),
            "CM_SERVICE_ABORT" to Regex("CM_SERVICE_ABORT|MM_CONNECTION_RELEASED|unexpected_connection_release", RegexOption.IGNORE_CASE),

            // === Enhanced 5G / modern IMSI catcher detections ===
            "5G_DOWNGRADE_ATTACK" to Regex("NR.*RRC.*redirect.*LTE|5G.*fallback.*2G|NSA.*anchor.*lost|NR.*SCG.*failure", RegexOption.IGNORE_CASE),
            "SUPI_EXPOSURE" to Regex("SUPI.*clear|IMSI.*5G.*plain|identity.*response.*SUPI|null.*scheme.*SUCI", RegexOption.IGNORE_CASE),
            "RRC_REDIRECT" to Regex("RRC.*Redirect|redirectedCarrierInfo|redirectionInfo|inter_freq_redirect", RegexOption.IGNORE_CASE),
            "MEASUREMENT_REPORT_FLOOD" to Regex("MeasurementReport.*MeasurementReport|measReport.*count.*[5-9]|excessive.*meas", RegexOption.IGNORE_CASE),
            "NAS_REJECT" to Regex("NAS.*REJECT|EMM.*SERVICE.*REJECT|5GMM.*reject|registration.*reject", RegexOption.IGNORE_CASE),
            "BASEBAND_EXPLOIT" to Regex("FATAL.*modem|modem.*crash|baseband.*assert|CP.*CRASH|CP_CRASH_REASON", RegexOption.IGNORE_CASE),

            // === 2025-2026 Enhanced Detections ===
            "SA_TO_NSA_DOWNGRADE" to Regex("SA.*mode.*lost|NR.*SA.*fallback.*NSA|standalone.*anchor.*released|SA.*deregistered.*NSA", RegexOption.IGNORE_CASE),
            "NR_FORCED_HANDOVER" to Regex("forced.*handover.*LTE|NR.*handover.*reject|inter_rat.*redirect.*lte|5G.*to.*4G.*forced", RegexOption.IGNORE_CASE),
            "TIMING_ADVANCE_ANOMALY" to Regex("timing.*advance.*value.*([3-9]\\d{2,}|[1-9]\\d{3,})|TA.*exceeds.*threshold|abnormal.*TA", RegexOption.IGNORE_CASE),
            "CELL_BARRING" to Regex("cell.*barred|system.*info.*barred|SIB1.*barred|access.*class.*barred", RegexOption.IGNORE_CASE),
            "EMERGENCY_REDIRECT" to Regex("emergency.*redirect|CSFB.*redirect|circuit.*switched.*fallback.*2G", RegexOption.IGNORE_CASE),
            "SILENT_REGISTRATION" to Regex("ATTACH.*without.*auth|no.*mutual.*auth|unilateral.*auth|one.way.*auth", RegexOption.IGNORE_CASE),
            "ABNORMAL_POWER_CONTROL" to Regex("TX.*power.*max|forced.*power.*increase|power.*control.*anomaly|abnormal.*UL.*power", RegexOption.IGNORE_CASE),
            "SMS_STATUS_REPORT_ABUSE" to Regex("SMS-STATUS-REPORT.*unexpected|delivery.*report.*spoof|status.*report.*flood", RegexOption.IGNORE_CASE),
            "GSMA_CATEGORY_ABUSE" to Regex("access.*category.*override|unified.*access.*control.*bypass|UAC.*violation", RegexOption.IGNORE_CASE),
            "PLMN_SPOOFING" to Regex("unexpected.*PLMN|PLMN.*mismatch|MCC.*MNC.*changed.*unexpectedly|fake.*operator.*name", RegexOption.IGNORE_CASE),

            // === 2025-2026 Advanced IMSI Catcher / Stingray Detections ===
            "RAPID_TMSI_REALLOC" to Regex("TMSI.*realloc|new.*TMSI.*assigned|P-TMSI.*change|GUTI.*realloc.*GUTI.*realloc", RegexOption.IGNORE_CASE),
            "SECURITY_MODE_REJECT" to Regex("SECURITY.*MODE.*REJECT|security.*mode.*fail|SMC.*rejected|integrity.*check.*fail", RegexOption.IGNORE_CASE),
            "REDIRECTION_LOOP" to Regex("redirect.*redirect.*redirect|consecutive.*redirect|redirection.*loop|inter_freq.*redirect.*inter_freq", RegexOption.IGNORE_CASE),
            "NULL_INTEGRITY" to Regex("integrity.*algorithm.*null|NIA0|EIA0.*only|no.*integrity.*protection", RegexOption.IGNORE_CASE),
            "FAKE_EMERGENCY_ALERT" to Regex("ETWS.*primary|CMAS.*alert.*unexpected|CB.*channel.*4370|CB.*channel.*4383|emergency.*alert.*no.*source", RegexOption.IGNORE_CASE),
            "N2_INTERFACE_ANOMALY" to Regex("N2.*interface.*error|AMF.*connection.*lost|NGAP.*failure|gNB.*unreachable", RegexOption.IGNORE_CASE),
            "BEARER_DOWNGRADE" to Regex("DRB.*release.*all|no.*dedicated.*bearer|default.*bearer.*only|QoS.*flow.*deleted.*all", RegexOption.IGNORE_CASE),
            "SLICING_ATTACK" to Regex("NSSAI.*reject|slice.*denied|network.*slice.*unavailable|S-NSSAI.*not.*allowed", RegexOption.IGNORE_CASE)
        )
    }

    // Dedup: don't alert the same event type too frequently
    private val lastAlertTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val alertCooldownMs = 30_000L

    fun start() {
        if (monitorJob?.isActive == true) return
        
        monitorJob = monitorScope.launch {
            Log.d(TAG, "Starting Radio Log Monitor (enhanced)...")
            try {
                val clearProcess = Runtime.getRuntime().exec("logcat -b radio -c")
                clearProcess.waitFor(5, TimeUnit.SECONDS)
                
                // Read both radio and main buffers (AIMSICD technique)
                val process = Runtime.getRuntime().exec("logcat -b radio -b main -v threadtime")
                logcatProcess = process
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                var line: String?
                while (isActive) {
                    line = reader.readLine() ?: break
                    analyzeLine(line)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Radio Monitor Error: ${e.message}")
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        try {
            logcatProcess?.destroyForcibly()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to destroy logcat process: ${e.message}")
        }
        logcatProcess = null
        Log.d(TAG, "Radio Log Monitor stopped.")
    }

    private fun analyzeLine(line: String) {
        SUSPICIOUS_PATTERNS.forEach { (type, regex) ->
            val match = regex.find(line)
            if (match != null) {
                val now = System.currentTimeMillis()
                val lastTime = lastAlertTime[type] ?: 0L
                if (now - lastTime > alertCooldownMs) {
                    lastAlertTime[type] = now
                    Log.w(TAG, "Suspicious Radio Event: $type -> $line")
                    triggerAlert(type, "Detected via Radio Log: ${match.value}")

                    // Feed auth/attach rejects into RilDefenderEngine for advanced analysis
                    when (type) {
                        "AUTH_REJECT" -> {
                            val cause = Regex("cause.*#?(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            RilDefenderEngine.recordAuthReject(cause)
                        }
                        "IMSI_ATTACH_ANOMALY" -> {
                            val cause = Regex("cause.*#?(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            RilDefenderEngine.recordAttachReject(cause)
                        }
                        "5G_DOWNGRADE_ATTACK", "SA_TO_NSA_DOWNGRADE", "NR_FORCED_HANDOVER" -> {
                            RilDefenderEngine.detectDowngradeAttack()
                        }
                    }
                }
            }
        }
    }

    private fun triggerAlert(type: String, description: String) {
        val severity = when (type) {
            "CIPHER_DISABLED", "NULL_CIPHER_MODE" -> 100
            "SILENT_CALL", "FAKE_CELL_DETECTED", "IDENTITY_REQUEST" -> 90
            "TYPE0_SMS", "SILENT_SMS_DELIVER", "AUTH_REJECT", "SUPI_EXPOSURE" -> 85
            "SILENT_PING", "RIL_REJECT_CAUSE", "LOCATION_UPDATE_REJECT" -> 75
            "BINARY_SMS", "WAP_PUSH_INJECT", "PAGING_FLOOD" -> 70
            "IMSI_ATTACH_ANOMALY", "TRACKING_AREA_REJECT" -> 65
            "5G_DOWNGRADE_ATTACK", "SA_TO_NSA_DOWNGRADE", "NR_FORCED_HANDOVER" -> 90
            "TIMING_ADVANCE_ANOMALY", "ABNORMAL_POWER_CONTROL" -> 80
            "CELL_BARRING", "EMERGENCY_REDIRECT" -> 75
            "SILENT_REGISTRATION", "PLMN_SPOOFING" -> 85
            "SMS_STATUS_REPORT_ABUSE" -> 70
            "GSMA_CATEGORY_ABUSE", "RRC_REDIRECT" -> 75
            "BASEBAND_EXPLOIT" -> 100
            "MEASUREMENT_REPORT_FLOOD", "NAS_REJECT" -> 70
            "RAPID_TMSI_REALLOC" -> 80
            "SECURITY_MODE_REJECT", "NULL_INTEGRITY" -> 95
            "REDIRECTION_LOOP" -> 75
            "FAKE_EMERGENCY_ALERT" -> 90
            "N2_INTERFACE_ANOMALY" -> 70
            "BEARER_DOWNGRADE" -> 65
            "SLICING_ATTACK" -> 75
            else -> 60
        }
        val intent = Intent(ACTION_FORENSIC).apply {
            setPackage(context.packageName)
            putExtra("threat", severity)
            putExtra("reason", "[$type] $description")
            putExtra("eventType", "RADIO_ANOMALY")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendBroadcast(intent, PERMISSION_FORENSIC)
    }
}
