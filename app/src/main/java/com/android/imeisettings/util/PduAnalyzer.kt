package com.android.imeisettings.util

import android.telephony.SmsMessage
import android.util.Log

/**
 * SMS PDU Analyzer — full GSM 03.40 / 03.38 / TS 23.038 compliance.
 *
 * Attack detection based on:
 * - RILDefender (NDSS 2023, OSUSecLab) — Silent SMS, SIMJacker, WIBAttack, Flash SMS, FBS, Proactive SIM
 * - GSM 03.38 spec — GSM 7-bit with extended alphabet (€, {, }, [, ], etc.)
 * - smspdudecoder — DCS group parsing, UDH Information Element decoding
 * - node-sms-pdu — BCD address decoding, PDU field structure
 */
object PduAnalyzer {
    private const val TAG = "PduAnalyzer"

    // PDU replay detection cache (hash -> timestamp)
    private val recentPduHashes = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    // RILDefender FBS threshold: -40 dBm (FBS-Radar, NDSS 2017)
    const val FBS_RSSI_THRESHOLD = -40

    // SIMJacker / WIBAttack STK command bytes (from RILDefender NDSS 2023)
    private const val STK_SETUP_CALL = 0x10
    private const val STK_SEND_SS = 0x11
    private const val STK_SEND_USSD = 0x12
    private const val STK_SEND_SMS = 0x13
    private const val STK_SEND_DTMF = 0x14
    private const val STK_LAUNCH_BROWSER = 0x15
    private const val STK_PROVIDE_LOCAL_INFO = 0x26
    private const val STK_OPEN_CHANNEL = 0x40
    private const val STK_CLOSE_CHANNEL = 0x41
    private const val STK_SEND_DATA = 0x43
    private const val STK_RUN_AT_CMD = 0x34
    private const val STK_POWER_OFF_CARD = 0x32
    private const val STK_POWER_ON_CARD = 0x31
    private const val STK_GET_READER_STATUS = 0x33
    private const val STK_RECEIVE_DATA = 0x42
    private const val STK_DISPLAY_TEXT = 0x21

    // SIMJacker destination ports
    private const val SIMJACKER_PORT = 0x0B84  // 2948 — SIM Toolkit Security port
    private const val WIB_PORT = 0x0B85         // 2949 — WIB (Wireless Internet Browser) attack port

    // IMSI Catcher / FBS fingerprint constants (RILDefender + SRLabs)
    private const val IMSI_CATCHER_CIPHER_A5_0 = 0  // No encryption — strong FBS indicator
    private const val NORMAL_MIN_LAC = 1
    private const val NORMAL_MAX_LAC = 65534
    private const val SUSPICIOUS_CID_THRESHOLD = 10 // Very low CID often used by portable FBS

    // RILDefender NDSS 2023 — additional attack signature byte patterns
    private val BINARY_OTA_ENVELOPE = byteArrayOf(0xD0.toByte(), 0x09, 0x81.toByte(), 0x03)
    private val ENVELOPE_SMS_PP = byteArrayOf(0xD1.toByte(), 0x09, 0x82.toByte())
    private val BEARER_INDEPENDENT_PROTOCOL = byteArrayOf(0xD0.toByte(), 0x00, 0x40)

    // GSM 7-bit default alphabet (GSM 03.38)
    private val GSM7_DEFAULT = charArrayOf(
        '@', '£', '$', '¥', 'è', 'é', 'ù', 'ì', 'ò', 'Ç', '\n', 'Ø', 'ø', '\r', 'Å', 'å',
        'Δ', '_', 'Φ', 'Γ', 'Λ', 'Ω', 'Π', 'Ψ', 'Σ', 'Θ', 'Ξ', '\u001B', 'Æ', 'æ', 'ß', 'É',
        ' ', '!', '"', '#', '¤', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?',
        '¡', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
        'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'Ä', 'Ö', 'Ñ', 'Ü', '§',
        '¿', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o',
        'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'ä', 'ö', 'ñ', 'ü', 'à'
    )

    // GSM 7-bit extended alphabet (via escape 0x1B)
    private val GSM7_EXTENDED = mapOf(
        10 to '\u000C',  // Form feed
        20 to '^',
        40 to '{',
        41 to '}',
        47 to '\\',
        60 to '[',
        61 to '~',
        62 to ']',
        64 to '|',
        101 to '€'
    )

    data class AnalysisResult(
        val isThreat: Boolean,
        val threatType: String?,
        val threatLevel: ThreatLevel = ThreatLevel.NONE,
        val description: String,
        val pduHex: String,
        val sender: String,
        val body: String,
        val sca: String?,
        val protocolId: Int,
        val dcs: Int,
        val dcsEncoding: DcsEncoding = DcsEncoding.GSM7,
        val dcsMessageClass: Int = -1,
        val hasUdh: Boolean = false,
        val udhData: String? = null,
        val udhPorts: PortInfo? = null,
        val isFbsSuspect: Boolean = false,
        val honeypotTriggered: Boolean = false,
        val detectedAttacks: List<String> = emptyList()
    )

    enum class ThreatLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

    enum class DcsEncoding { GSM7, BINARY, UCS2, UNKNOWN }

    data class PortInfo(val destPort: Int, val srcPort: Int, val is16bit: Boolean)

    data class RawPduFields(
        val firstByte: Int,
        val scaLength: Int,
        val scaNumber: String,
        val tpMti: Int,
        val tpUdhi: Boolean,
        val tpSri: Boolean,
        val tpMms: Boolean,
        val tpRp: Boolean,
        val tpLp: Boolean,
        val oaLength: Int,
        val oaType: Int,
        val oaDigits: String,
        val pid: Int,
        val dcs: Int,
        val scts: ByteArray,
        val udl: Int,
        val userData: ByteArray
    )

    /**
     * Full GSM 03.40 SMS-DELIVER PDU parser.
     * Parses: SCA, TP header flags, OA, PID, DCS, SCTS, UDL, UD.
     */
    fun parseRawPdu(pdu: ByteArray): RawPduFields? {
        if (pdu.size < 10) return null
        return try {
            var offset = 0

            // SCA (Service Center Address)
            val scaLength = pdu[offset].toInt() and 0xFF
            val scaNumber = if (scaLength > 0 && offset + scaLength < pdu.size) {
                val scaType = pdu[offset + 1].toInt() and 0xFF
                decodeBcdAddress(pdu, offset + 2, scaLength - 1, scaType)
            } else ""
            offset += 1 + scaLength

            if (offset >= pdu.size) return null

            // TP first octet flags (GSM 03.40 §9.2.3.1)
            val firstOctet = pdu[offset].toInt() and 0xFF
            val tpMti = firstOctet and 0x03         // bits 0-1: Message Type Indicator
            val tpMms = (firstOctet and 0x04) != 0  // bit 2: More Messages to Send
            val tpLp = (firstOctet and 0x08) != 0   // bit 3: Loop Prevention
            val tpSri = (firstOctet and 0x20) != 0  // bit 5: Status Report Indication
            val tpUdhi = (firstOctet and 0x40) != 0 // bit 6: User Data Header Indicator
            val tpRp = (firstOctet and 0x80) != 0   // bit 7: Reply Path
            offset++

            if (offset >= pdu.size) return null

            // TP-OA (Originating Address)
            val oaLength = pdu[offset].toInt() and 0xFF
            offset++
            if (offset >= pdu.size) return null
            val oaType = pdu[offset].toInt() and 0xFF
            offset++
            val oaByteCount = (oaLength + 1) / 2
            val oaDigits = if (oaByteCount > 0 && offset + oaByteCount <= pdu.size) {
                // Check if alphanumeric (TON = 0x50 = alphanumeric)
                if ((oaType and 0x70) == 0x50) {
                    decodeGsm7BitPacked(pdu, offset, oaByteCount, (oaLength * 4) / 7)
                } else {
                    decodeBcdAddress(pdu, offset, oaByteCount, oaType)
                }
            } else ""
            offset += oaByteCount

            if (offset >= pdu.size) return null

            // TP-PID
            val pid = pdu[offset].toInt() and 0xFF
            offset++

            if (offset >= pdu.size) return null

            // TP-DCS
            val dcs = pdu[offset].toInt() and 0xFF
            offset++

            // TP-SCTS (7 bytes)
            val scts = if (offset + 7 <= pdu.size) pdu.copyOfRange(offset, offset + 7) else ByteArray(7)
            offset += 7

            // TP-UDL
            val udl = if (offset < pdu.size) pdu[offset].toInt() and 0xFF else 0
            offset++

            // TP-UD (User Data)
            val userData = if (offset < pdu.size) pdu.copyOfRange(offset, pdu.size) else ByteArray(0)

            RawPduFields(
                firstByte = firstOctet,
                scaLength = scaLength,
                scaNumber = scaNumber,
                tpMti = tpMti,
                tpUdhi = tpUdhi,
                tpSri = tpSri,
                tpMms = tpMms,
                tpRp = tpRp,
                tpLp = tpLp,
                oaLength = oaLength,
                oaType = oaType,
                oaDigits = oaDigits,
                pid = pid,
                dcs = dcs,
                scts = scts,
                udl = udl,
                userData = userData
            )
        } catch (e: Exception) {
            Log.e(TAG, "Raw PDU parse error: ${e.message}")
            null
        }
    }

    fun analyze(sms: SmsMessage): AnalysisResult {
        return analyze(sms, signalDbm = null)
    }

    /**
     * Complete PDU threat analysis with RILDefender-grade attack detection.
     * Detects: Silent SMS, SIMJacker, WIBAttack, Flash SMS, FBS, Proactive SIM,
     * Binary OTA, Replace SMS, SIM Data Download, ME Data Download, USSD injection.
     */
    fun analyze(sms: SmsMessage, signalDbm: Int?): AnalysisResult {
        val pdu = sms.pdu
        val pduHex = pdu.joinToString("") { "%02x".format(it) }
        val sender = sms.originatingAddress ?: "Unknown"
        val body = sms.messageBody ?: ""
        val pid = sms.protocolIdentifier
        val sca = sms.serviceCenterAddress

        var dcs = 0
        var hasUdhi = false
        var udhData: String? = null
        var udhPorts: PortInfo? = null

        val rawFields = parseRawPdu(pdu)
        if (rawFields != null) {
            dcs = rawFields.dcs
            hasUdhi = rawFields.tpUdhi

            if (hasUdhi && rawFields.userData.isNotEmpty()) {
                val udhLen = rawFields.userData[0].toInt() and 0xFF
                val udhEnd = minOf(udhLen + 1, rawFields.userData.size)
                udhData = rawFields.userData.copyOfRange(0, udhEnd).joinToString("") { "%02x".format(it) }
                udhPorts = extractPorts(rawFields.userData)
            }
        }

        val attacks = mutableListOf<String>()
        var threatLevel = ThreatLevel.NONE
        var isFbsSuspect = false
        val description = StringBuilder()

        // === RILDefender-style multi-layer analysis ===

        // 1. Silent SMS (Type 0) — TP-PID 0x40
        if (pid == 0x40) {
            attacks.add("SILENT_SMS")
            threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
            description.append("Silent SMS (Type 0 Ping) — tracking/surveillance indicator. ")
        }

        // 2. SIM Data Download (PID 0x7F) — per RILDefender binary_sms policy
        if (pid == 0x7F) {
            attacks.add("SIM_DATA_DOWNLOAD")
            threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
            description.append("SIM Data Download command (PID 0x7F). ")
        }

        // 3. ME Data Download (PID 0x7E)
        if (pid == 0x7E) {
            attacks.add("ME_DATA_DOWNLOAD")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("ME Data Download (PID 0x7E). ")
        }

        // 4. USIM Data Download (PID 0x7D)
        if (pid == 0x7D) {
            attacks.add("USAT_DATA")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("USIM Data Download (PID 0x7D). ")
        }

        // 5. Replace Short Message types (PID 0x41-0x47)
        if (pid in 0x41..0x47) {
            attacks.add("REPLACE_SMS_TYPE_${pid - 0x40}")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("Replace SMS type ${pid - 0x40} (PID 0x${"%02x".format(pid)}). ")
        }

        // 6. Proactive SIM SMS detection (from RILDefender)
        // STK commands embedded in SMS: SEND_SMS(0x13), RUN_AT_CMD(0x34), etc.
        if (hasUdhi && rawFields != null) {
            val stkCommands = detectStkCommands(rawFields.userData)
            if (stkCommands.isNotEmpty()) {
                attacks.addAll(stkCommands)
                threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                description.append("Proactive SIM commands detected: ${stkCommands.joinToString()}. ")
            }
        }

        // 7. Flash SMS (Class 0) — immediate display, no storage
        if (sms.messageClass == SmsMessage.MessageClass.CLASS_0) {
            attacks.add("FLASH_SMS")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("Flash SMS (Class 0) — immediate display. ")
        }

        // 8. SIMJacker / WIBAttack port detection
        if (udhPorts != null) {
            when (udhPorts.destPort) {
                SIMJACKER_PORT -> {
                    attacks.add("SIMJACKER")
                    threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                    description.append("SIMJacker attack — port $SIMJACKER_PORT (SIM Toolkit Security). ")
                }
                in 9200..9203 -> {
                    attacks.add("WAP_CONNECTION_ATTACK")
                    threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                    description.append("WAP connection provisioning (port ${udhPorts.destPort}). ")
                }
                2948 -> {
                    attacks.add("WAP_PUSH_PORT")
                    threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                    description.append("WAP Push via port 2948. ")
                }
            }
        }

        // 9. DCS analysis (TS 23.038)
        val dcsResult = analyzeDcs(dcs)
        if (dcsResult.threatType != null) {
            attacks.add(dcsResult.threatType)
            threatLevel = maxOf(threatLevel, dcsResult.level)
            description.append(dcsResult.description)
        }

        // 10. UDH deep analysis
        if (hasUdhi && rawFields != null && rawFields.userData.isNotEmpty()) {
            val udhResult = analyzeUdh(rawFields.userData)
            if (udhResult != null) {
                attacks.add(udhResult)
                threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                description.append("Suspicious UDH: $udhResult. ")
            }
        }

        // 11. FBS (Fake Base Station) detection — RILDefender RSSI threshold
        if (signalDbm != null && signalDbm > FBS_RSSI_THRESHOLD) {
            isFbsSuspect = true
            attacks.add("FBS_SUSPECT")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("FBS suspect: signal abnormally strong (${signalDbm} dBm > ${FBS_RSSI_THRESHOLD} dBm threshold). ")
        }

        // 12. Binary payload without text
        if (body.isEmpty() && pdu.size > 20) {
            if (attacks.isEmpty()) {
                attacks.add("BINARY_PAYLOAD")
                threatLevel = maxOf(threatLevel, ThreatLevel.LOW)
            }
            description.append("Binary payload without text body (${pdu.size} bytes). ")
        }

        // 13. Missing SCA
        if (sca.isNullOrEmpty()) {
            description.append("Warning: Missing Service Center Address. ")
        }

        // 14. Custom PID attacks (PID 0x20 — IMI/telematic interworking)
        if (pid == 0x20) {
            attacks.add("CUSTOM_PID_ATTACK")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("Custom PID attack (telematic interworking 0x20). ")
        }

        // === RILDefender NDSS 2023 — Extended Attack Signatures ===

        // 15. WIBAttack detection (port 2949)
        if (udhPorts != null && udhPorts.destPort == WIB_PORT) {
            attacks.add("WIB_ATTACK")
            threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
            description.append("WIBAttack — Wireless Internet Browser exploit (port $WIB_PORT). ")
        }

        // 16. Binary OTA Envelope / SMS-PP Download in payload
        if (rawFields != null && rawFields.userData.size >= 4) {
            val ud = rawFields.userData
            val dataStart = if (hasUdhi) (ud[0].toInt() and 0xFF) + 1 else 0
            if (dataStart < ud.size) {
                val payload = ud.copyOfRange(dataStart, ud.size)
                if (containsPattern(payload, BINARY_OTA_ENVELOPE)) {
                    if ("SIM_DATA_DOWNLOAD" !in attacks) {
                        attacks.add("BINARY_OTA_ENVELOPE")
                        threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                        description.append("Binary OTA envelope (proactive SIM command container). ")
                    }
                }
                if (containsPattern(payload, ENVELOPE_SMS_PP)) {
                    attacks.add("SMS_PP_ENVELOPE")
                    threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                    description.append("SMS-PP Download envelope detected. ")
                }
                if (containsPattern(payload, BEARER_INDEPENDENT_PROTOCOL)) {
                    attacks.add("BIP_CHANNEL")
                    threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                    description.append("Bearer Independent Protocol channel open attempt. ")
                }
            }
        }

        // 17. IMSI Catcher fingerprint — suspicious sender format
        if (sender.matches(Regex("^\\+?0{5,}$")) || sender == "000" || sender == "0000") {
            attacks.add("IMSI_CATCHER_SPOOFED_SENDER")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("IMSI Catcher fingerprint: spoofed zero-sender ($sender). ")
        }

        // 18. Loop Prevention flag set (TP-LP) — indicates message relay/injection
        if (rawFields != null && rawFields.tpLp) {
            attacks.add("LOOP_PREVENTION_FLAG")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("Loop Prevention flag set — possible message injection. ")
        }

        // 19. Reply Path flag set (TP-RP) — used to redirect replies
        if (rawFields != null && rawFields.tpRp && attacks.isNotEmpty()) {
            attacks.add("REPLY_PATH_REDIRECT")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("Reply Path set on suspicious message — reply redirection. ")
        }

        // 20. Concatenated SMS with suspicious segment count (fragmentation attack)
        if (hasUdhi && rawFields != null && rawFields.userData.size >= 6) {
            val concatInfo = extractConcatenatedInfo(rawFields.userData)
            if (concatInfo != null && concatInfo.totalParts > 20) {
                attacks.add("FRAGMENTATION_ATTACK")
                threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
                description.append("Excessive SMS fragmentation (${concatInfo.totalParts} parts) — possible buffer overflow attempt. ")
            }
        }

        // 21. PID 0x3F — Return Call Message (used by IMSI catchers for call-back attacks)
        if (pid == 0x3F) {
            attacks.add("RETURN_CALL_MSG")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("Return Call Message (PID 0x3F) — possible IMSI catcher call-back attack. ")
        }

        // 22. PID 0x5E-0x7C — SC-specific use (often abused by fake base stations)
        if (pid in 0x5E..0x7C) {
            attacks.add("SC_SPECIFIC_PID")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("SC-specific PID 0x${"%02x".format(pid)} — non-standard protocol. ")
        }

        // 23. DCS indicates reserved coding group (possible exploit)
        // NOTE: High nibble 0x04-0x07 is normal "General Data Coding" (GSM 03.38 §4)
        // Only 0x08-0x0B are truly reserved and suspicious
        val dcsHighNibble = (dcs shr 4) and 0x0F
        if (dcsHighNibble in 0x08..0x0B) {
            attacks.add("RESERVED_DCS_GROUP")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("Reserved DCS coding group 0x${"%x".format(dcsHighNibble)} — possible exploit. ")
        }

        // 24. SMS-STATUS-REPORT spoofing detection (TP-MTI = 10, forged delivery reports)
        if (rawFields != null && (rawFields.firstByte and 0x03) == 0x02) {
            val senderStr = sender.replace("+", "").replace(" ", "")
            if (senderStr.length < 4 || senderStr.all { it == '0' }) {
                attacks.add("STATUS_REPORT_SPOOF")
                threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                description.append("Spoofed SMS-STATUS-REPORT from suspicious origin. ")
            }
        }

        // 25. eSIM profile manipulation via SMS OTA (Remote SIM Provisioning attack)
        if (hasUdhi && rawFields != null && rawFields.userData.size > 4) {
            val udhLen = rawFields.userData[0].toInt() and 0xFF
            val dataStart = udhLen + 1
            if (dataStart < rawFields.userData.size) {
                val payload = rawFields.userData.copyOfRange(dataStart, rawFields.userData.size)
                // Check for GlobalPlatform Secure Channel Protocol markers
                if (payload.size >= 2 && payload[0].toInt() and 0xFF == 0x80 && payload[1].toInt() and 0xFF == 0x50) {
                    attacks.add("ESIM_PROFILE_MANIPULATION")
                    threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                    description.append("eSIM Remote Provisioning command detected — possible profile hijack. ")
                }
            }
        }

        // 26. USSD-over-SMS injection (encoded USSD commands in SMS body)
        if (body.matches(Regex(".*\\*#?[0-9*#]{3,}#.*")) && pid != 0x00) {
            attacks.add("USSD_INJECTION")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("Possible USSD injection via SMS (USSD code in body with non-standard PID). ")
        }

        // 27. Null-terminated payload (binary exploit attempt)
        if (rawFields != null && rawFields.userData.size >= 8) {
            var nullCount = 0
            for (b in rawFields.userData) {
                if (b.toInt() == 0) nullCount++
            }
            if (nullCount > rawFields.userData.size * 0.7 && pid != 0x00) {
                attacks.add("NULL_PAYLOAD_EXPLOIT")
                threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                description.append("Binary null-padded payload (${nullCount}/${rawFields.userData.size} null bytes) — possible exploit. ")
            }
        }

        // 28. SMS body phishing/malware URL detection
        if (body.isNotEmpty()) {
            val bodyResult = analyzeBodyContent(body, sender)
            if (bodyResult.isNotEmpty()) {
                attacks.addAll(bodyResult.map { it.first })
                for ((attack, desc, level) in bodyResult) {
                    threatLevel = maxOf(threatLevel, level)
                    description.append("$desc ")
                }
            }
        }

        // 29. Timestamp anomaly detection (SCTS)
        if (rawFields != null && rawFields.scts.size >= 7) {
            val tsResult = analyzeScts(rawFields.scts)
            if (tsResult != null) {
                attacks.add(tsResult.first)
                threatLevel = maxOf(threatLevel, tsResult.second)
                description.append("${tsResult.third} ")
            }
        }

        // 30. Encoding mismatch — DCS claims GSM7 but payload has high bytes
        if (rawFields != null && dcsResult.encoding == DcsEncoding.GSM7 && rawFields.userData.isNotEmpty()) {
            val dataStart = if (hasUdhi) ((rawFields.userData[0].toInt() and 0xFF) + 1).coerceAtMost(rawFields.userData.size) else 0
            if (dataStart < rawFields.userData.size) {
                var highByteCount = 0
                for (i in dataStart until rawFields.userData.size) {
                    if (rawFields.userData[i].toInt() and 0xFF > 0x7F) highByteCount++
                }
                if (highByteCount > rawFields.userData.size / 3 && rawFields.userData.size > 4) {
                    attacks.add("ENCODING_MISMATCH")
                    threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
                    description.append("DCS/encoding mismatch: GSM7 declared but $highByteCount/${rawFields.userData.size} bytes >0x7F — possible binary injection. ")
                }
            }
        }

        // 31. PDU replay detection (hash-based)
        if (rawFields != null) {
            val pduHash = pduHex.hashCode()
            val now = System.currentTimeMillis()
            synchronized(recentPduHashes) {
                val lastSeen = recentPduHashes[pduHash]
                if (lastSeen != null && now - lastSeen < 60_000) {
                    attacks.add("PDU_REPLAY")
                    threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                    description.append("PDU replay detected — identical PDU received within 60s. ")
                }
                recentPduHashes[pduHash] = now
                // Cleanup old entries
                recentPduHashes.entries.removeAll { now - it.value > 300_000 }
            }
        }

        // 32. Abnormal sender length (OA length exploitation)
        if (rawFields != null && rawFields.oaLength > 20) {
            attacks.add("OA_LENGTH_OVERFLOW")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("Abnormal sender address length (${rawFields.oaLength} digits) — possible buffer overflow. ")
        }

        // 33. TP-MMS (More Messages to Send) with aggressive delivery
        if (rawFields != null && rawFields.tpMms && attacks.isNotEmpty()) {
            description.append("MMS flag set on suspicious message — batch attack. ")
        }

        // 34. SS7/MAP attack detection — CAP/INAP commands smuggled via SMS
        if (rawFields != null && rawFields.userData.size >= 4) {
            val dataStart = if (hasUdhi) ((rawFields.userData[0].toInt() and 0xFF) + 1).coerceAtMost(rawFields.userData.size) else 0
            if (dataStart < rawFields.userData.size) {
                val payload = rawFields.userData.copyOfRange(dataStart, rawFields.userData.size)
                // TCAP begin tag (0x62) followed by MAP operation code
                if (payload.size >= 3 && payload[0].toInt() and 0xFF == 0x62) {
                    attacks.add("SS7_MAP_INJECTION")
                    threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                    description.append("SS7/MAP protocol injection detected (TCAP begin tag). ")
                }
                // ASN.1 SEQUENCE tag (0x30) with MAP opcode for SendRoutingInfo/ProvideSubscriberInfo
                if (payload.size >= 4 && payload[0].toInt() and 0xFF == 0x30) {
                    val potentialOpcode = payload.getOrNull(3)?.toInt()?.and(0xFF) ?: 0
                    if (potentialOpcode in listOf(0x16, 0x46, 0x47, 0x4D, 0x22, 0x2D)) {
                        attacks.add("SS7_MAP_LOCATION_QUERY")
                        threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                        description.append("SS7 MAP location query command (opcode 0x${"%02x".format(potentialOpcode)}) smuggled via SMS. ")
                    }
                }
            }
        }

        // 35. Rapid SMS burst detection — multiple SMS from same sender within seconds
        if (rawFields != null) {
            val senderKey = sender.replace("+", "").takeLast(10)
            val now = System.currentTimeMillis()
            synchronized(recentPduHashes) {
                val burstKey = "burst_$senderKey".hashCode()
                val lastBurst = recentPduHashes[burstKey]
                if (lastBurst != null && now - lastBurst < 5_000) {
                    val burstCountKey = "burstcnt_$senderKey".hashCode()
                    val cnt = (recentPduHashes[burstCountKey] ?: 0) + 1
                    recentPduHashes[burstCountKey] = cnt
                    if (cnt >= 5) {
                        attacks.add("SMS_BURST_FLOOD")
                        threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                        description.append("SMS burst flood: $cnt messages from $sender within 5s. ")
                    }
                }
                recentPduHashes[burstKey] = now
            }
        }

        // 36. WAP Push binary SMS detection (OTA provisioning attack)
        if (rawFields != null && hasUdhi && udhPorts != null) {
            val dstPort = udhPorts.destPort
            if (dstPort == 2948 || dstPort == 2949) {
                if (dstPort == 2948) {
                    attacks.add("SIMJACKER_PORT_DETECTED")
                    threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                    description.append("SIMjacker attack port 2948 detected — STK command injection. ")
                } else {
                    attacks.add("WIBATTACK_PORT_DETECTED")
                    threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                    description.append("WIBattack port 2949 detected — WIB exploitation attempt. ")
                }
            }
            if (dstPort == 2948 || dstPort == 2949 || dstPort == 9200 || dstPort == 9201 || dstPort == 9202 || dstPort == 9203) {
                val wapResult = decodeWapPushPayload(rawFields.userData, hasUdhi)
                if (wapResult != null) {
                    attacks.add("WAP_PUSH_OTA_ATTACK")
                    threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                    description.append("WAP Push OTA: $wapResult. ")
                }
            }
        }

        // 37. SIM Toolkit (STK) remote command detection
        if (rawFields != null && rawFields.userData.isNotEmpty()) {
            val stkResult = detectStkCommands(rawFields.userData, hasUdhi)
            if (stkResult != null) {
                attacks.add("STK_REMOTE_COMMAND")
                threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                description.append("STK remote command: $stkResult. ")
            }
        }

        // 38. SIMjacker S@T Browser exploit detection
        if (rawFields != null && rawFields.userData.size >= 6) {
            val simjackerResult = detectSimjackerExploit(rawFields.userData, hasUdhi)
            if (simjackerResult != null) {
                attacks.add("SIMJACKER_SAT_EXPLOIT")
                threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                description.append("SIMjacker S@T exploit: $simjackerResult. ")
            }
        }

        // 39. WIBattack detection (Wireless Internet Browser exploitation)
        if (rawFields != null && rawFields.userData.size >= 4) {
            val wibResult = detectWibAttack(rawFields.userData, hasUdhi)
            if (wibResult != null) {
                attacks.add("WIBATTACK_EXPLOIT")
                threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                description.append("WIBattack exploit: $wibResult. ")
            }
        }

        // 40. OTA SIM configuration attack detection
        if (rawFields != null && pid == 0x7F) {
            attacks.add("OTA_SIM_CONFIG")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("OTA SIM configuration message (PID=0x7F) — possible remote SIM update. ")
        }

        val isThreat = attacks.isNotEmpty()
        val honeypotTriggered = isThreat

        if (description.isEmpty()) {
            description.append("Standard SMS message.")
        }

        return AnalysisResult(
            isThreat = isThreat,
            threatType = attacks.firstOrNull(),
            threatLevel = threatLevel,
            description = description.toString().trim(),
            pduHex = pduHex,
            sender = sender,
            body = body,
            sca = sca,
            protocolId = pid,
            dcs = dcs,
            dcsEncoding = dcsResult.encoding,
            dcsMessageClass = dcsResult.messageClass,
            hasUdh = hasUdhi,
            udhData = udhData,
            udhPorts = udhPorts,
            isFbsSuspect = isFbsSuspect,
            honeypotTriggered = honeypotTriggered,
            detectedAttacks = attacks
        )
    }

    /**
     * Raw binary PDU analysis when SmsMessage isn't available.
     */
    fun analyzeRawPdu(pduBytes: ByteArray, signalDbm: Int? = null): AnalysisResult {
        val pduHex = pduBytes.joinToString("") { "%02x".format(it) }
        val rawFields = parseRawPdu(pduBytes) ?: return AnalysisResult(
            isThreat = false, threatType = null, description = "Failed to parse PDU",
            pduHex = pduHex, sender = "Unknown", body = "", sca = null,
            protocolId = 0, dcs = 0
        )

        val attacks = mutableListOf<String>()
        var threatLevel = ThreatLevel.NONE
        var isFbsSuspect = false
        var udhData: String? = null
        var udhPorts: PortInfo? = null
        val description = StringBuilder()

        val pid = rawFields.pid
        val dcs = rawFields.dcs

        // Silent SMS
        if (pid == 0x40) {
            attacks.add("SILENT_SMS")
            threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
            description.append("Silent SMS detected (Type 0). ")
        }

        // SIM Data Download / ME Data Download / USAT
        when (pid) {
            0x7F -> {
                attacks.add("SIM_DATA_DOWNLOAD")
                threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                description.append("SIM Data Download (PID 0x7F). ")
            }
            0x7E -> {
                attacks.add("ME_DATA_DOWNLOAD")
                threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                description.append("ME Data Download (PID 0x7E). ")
            }
            0x7D -> {
                attacks.add("USAT_DATA")
                threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                description.append("USIM Data Download (PID 0x7D). ")
            }
        }

        // SIM Toolkit / OTA
        if (pid in 0x41..0x47) {
            attacks.add("SIM_TOOLKIT_OTA")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("OTA command (PID: 0x${"%02x".format(pid)}). ")
        }

        // Flash SMS via DCS
        val dcsClass = dcs and 0x03
        if ((dcs and 0x10) == 0x10 && dcsClass == 0) {
            attacks.add("FLASH_SMS")
            threatLevel = maxOf(threatLevel, ThreatLevel.MEDIUM)
            description.append("Flash SMS (Class 0 via DCS). ")
        }

        // DCS analysis
        val dcsResult = analyzeDcs(dcs)
        if (dcsResult.threatType != null) {
            attacks.add(dcsResult.threatType)
            threatLevel = maxOf(threatLevel, dcsResult.level)
            description.append(dcsResult.description)
        }

        // UDH analysis
        if (rawFields.tpUdhi && rawFields.userData.isNotEmpty()) {
            val udhLen = rawFields.userData[0].toInt() and 0xFF
            val udhEnd = minOf(udhLen + 1, rawFields.userData.size)
            udhData = rawFields.userData.copyOfRange(0, udhEnd).joinToString("") { "%02x".format(it) }
            udhPorts = extractPorts(rawFields.userData)

            // SIMJacker port check
            if (udhPorts != null && udhPorts.destPort == SIMJACKER_PORT) {
                attacks.add("SIMJACKER")
                threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                description.append("SIMJacker attack detected (port $SIMJACKER_PORT). ")
            }

            // Proactive SIM STK commands
            val stkCommands = detectStkCommands(rawFields.userData)
            if (stkCommands.isNotEmpty()) {
                attacks.addAll(stkCommands)
                threatLevel = maxOf(threatLevel, ThreatLevel.CRITICAL)
                description.append("Proactive SIM: ${stkCommands.joinToString()}. ")
            }

            val udhResult = analyzeUdh(rawFields.userData)
            if (udhResult != null) {
                attacks.add(udhResult)
                threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
                description.append("Suspicious UDH ($udhResult). ")
            }
        }

        // FBS signal check — using RILDefender threshold
        if (signalDbm != null && signalDbm > FBS_RSSI_THRESHOLD) {
            isFbsSuspect = true
            attacks.add("FBS_SUSPECT")
            threatLevel = maxOf(threatLevel, ThreatLevel.HIGH)
            description.append("FBS suspect: signal ${signalDbm} dBm. ")
        }

        val isThreat = attacks.isNotEmpty()
        if (isThreat) { /* honeypot */ }

        if (description.isEmpty()) description.append("Standard SMS.")

        // Decode body based on DCS encoding
        val body = try {
            when (dcsResult.encoding) {
                DcsEncoding.UCS2 -> {
                    if (rawFields.tpUdhi) {
                        val udhLen = rawFields.userData[0].toInt() and 0xFF
                        val dataStart = udhLen + 1
                        if (dataStart < rawFields.userData.size) {
                            decodeUcs2(rawFields.userData, dataStart, rawFields.userData.size - dataStart)
                        } else ""
                    } else {
                        decodeUcs2(rawFields.userData, 0, rawFields.userData.size)
                    }
                }
                DcsEncoding.GSM7 -> {
                    if (!rawFields.tpUdhi) {
                        decodeGsm7BitDefault(rawFields.userData, rawFields.udl)
                    } else {
                        val udhLen = rawFields.userData[0].toInt() and 0xFF
                        val headerBits = (udhLen + 1) * 8
                        val headerSeptets = (headerBits + 6) / 7
                        decodeGsm7BitWithOffset(rawFields.userData, rawFields.udl, headerSeptets)
                    }
                }
                else -> ""
            }
        } catch (e: Exception) { "" }

        return AnalysisResult(
            isThreat = isThreat, threatType = attacks.firstOrNull(),
            threatLevel = threatLevel,
            description = description.toString().trim(),
            pduHex = pduHex, sender = rawFields.oaDigits, body = body,
            sca = rawFields.scaNumber.ifEmpty { null },
            protocolId = pid, dcs = dcs,
            dcsEncoding = dcsResult.encoding,
            dcsMessageClass = dcsResult.messageClass,
            hasUdh = rawFields.tpUdhi, udhData = udhData, udhPorts = udhPorts,
            isFbsSuspect = isFbsSuspect, honeypotTriggered = isThreat,
            detectedAttacks = attacks
        )
    }

    /**
     * WAP Push decoder (WSP/WTP layer).
     */
    fun decodeWapPush(data: ByteArray?): String {
        if (data == null || data.isEmpty()) return "Empty WAP Data"
        return try {
            val result = StringBuilder()
            var offset = 0

            // Transaction ID
            if (offset < data.size) {
                val transactionId = data[offset].toInt() and 0xFF
                result.append("TransID: $transactionId | ")
                offset++
            }

            // PDU Type
            if (offset < data.size) {
                val pduType = data[offset].toInt() and 0xFF
                val typeStr = when (pduType) {
                    0x06 -> "Push"
                    0x07 -> "Confirmed Push"
                    0x08 -> "Suspend"
                    0x09 -> "Resume"
                    else -> "Type: 0x${"%02x".format(pduType)}"
                }
                result.append("$typeStr | ")
                offset++
            }

            // Headers Length (uintvar)
            var headersLen = 0
            if (offset < data.size) {
                val uintvar = decodeUintvar(data, offset)
                headersLen = uintvar.first
                offset = uintvar.second
            }

            // Content-Type
            if (offset < data.size) {
                val contentType = data[offset].toInt() and 0xFF
                val contentTypeStr = decodeWspContentType(contentType)
                result.append("Content: $contentTypeStr | ")
                offset++

                // Skip remaining headers
                val headerEnd = offset + headersLen - 1
                if (headerEnd > offset && headerEnd < data.size) {
                    offset = headerEnd
                }
            }

            // Extract readable text from remaining payload
            val bodyStart = offset
            val textChars = data.drop(bodyStart)
                .filter { (it.toInt() and 0xFF) in 32..126 }
                .map { it.toInt().toChar() }
                .joinToString("")

            if (textChars.length >= 3) {
                result.append("Payload: $textChars")
            } else {
                result.append("Binary (${data.size - bodyStart} bytes)")
            }

            // Detect threat content types
            val hex = data.joinToString("") { "%02x".format(it) }
            val threats = mutableListOf<String>()

            if (contentTypeContainsSi(data, bodyStart)) {
                threats.add("Service Indication")
            }
            if (contentTypeContainsSl(data, bodyStart)) {
                threats.add("Service Loading — POTENTIAL THREAT")
            }
            // OMA DRM rights delivery
            if (hex.contains("4803") || hex.contains("4a03")) {
                threats.add("OMA DRM Rights")
            }
            // OTA provisioning — check for WAP OTA content type (0xB0 = application/vnd.wap.connectivity-wbxml)
            if (offset < data.size && (data[offset].toInt() and 0xFF) == 0xB0) {
                threats.add("OTA Provisioning")
            }
            // MMS notification
            if (hex.contains("3e") && textChars.contains("http")) {
                threats.add("MMS Notification with URL")
            }

            // Extract URLs from WAP Push payload for threat analysis
            val urlPattern = Regex("https?://[^\\s\\x00-\\x1f]+", RegexOption.IGNORE_CASE)
            val payloadUrls = urlPattern.findAll(textChars).map { it.value }.toList()
            for (url in payloadUrls) {
                if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(url)) {
                    threats.add("WAP Push IP-URL: $url")
                }
                if (Regex("\\.(apk|dex|exe|sh|bin)", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                    threats.add("WAP Push MALWARE-URL: $url")
                }
            }

            // Provisioning document attack (APN/proxy hijack)
            if (textChars.contains("NAPDEF") || textChars.contains("PXLOGICAL") ||
                textChars.contains("BOOTSTRAP") || textChars.contains("ACCESS")) {
                threats.add("OTA Config Provisioning — APN/Proxy hijack risk")
            }

            if (threats.isNotEmpty()) {
                result.append(" [${threats.joinToString(", ")}]")
            }

            result.toString()
        } catch (e: Exception) {
            val text = data.filter { (it.toInt() and 0xFF) in 32..126 }
                .map { it.toInt().toChar() }.joinToString("")
            if (text.length > 5) "WAP: $text" else "Binary WAP (${data.size} bytes)"
        }
    }

    // ======================== DCS Analysis (TS 23.038 §4) ========================

    data class DcsAnalysisResult(
        val encoding: DcsEncoding,
        val messageClass: Int,
        val threatType: String?,
        val level: ThreatLevel,
        val description: String
    )

    private fun analyzeDcs(dcs: Int): DcsAnalysisResult {
        val highNibble = (dcs shr 4) and 0x0F
        var encoding = DcsEncoding.GSM7
        var messageClass = -1
        var threatType: String? = null
        var level = ThreatLevel.NONE
        val desc = StringBuilder()

        when {
            // General data coding groups (00xx)
            highNibble in 0x00..0x03 -> {
                val codingBits = (dcs shr 2) and 0x03
                encoding = when (codingBits) {
                    0 -> DcsEncoding.GSM7
                    1 -> DcsEncoding.BINARY
                    2 -> DcsEncoding.UCS2
                    else -> DcsEncoding.UNKNOWN
                }
                if ((dcs and 0x10) != 0) {
                    messageClass = dcs and 0x03
                }
            }
            // Message Waiting Indication — Discard (1100)
            highNibble == 0x0C -> {
                encoding = DcsEncoding.GSM7
                threatType = "MSG_WAITING_DISCARD"
                level = ThreatLevel.LOW
                desc.append("Message Waiting Indication — Discard (DCS 0x${"%02x".format(dcs)}). ")
            }
            // Message Waiting Indication — Store GSM (1101)
            highNibble == 0x0D -> {
                encoding = DcsEncoding.GSM7
                threatType = "MSG_WAITING_STORE"
                level = ThreatLevel.LOW
                desc.append("Message Waiting Indication — Store (DCS 0x${"%02x".format(dcs)}). ")
            }
            // Message Waiting Indication — Store UCS2 (1110)
            highNibble == 0x0E -> {
                encoding = DcsEncoding.UCS2
                threatType = "MSG_WAITING_UCS2"
                level = ThreatLevel.LOW
                desc.append("Message Waiting — UCS-2 (DCS 0x${"%02x".format(dcs)}). ")
            }
            // Data coding/message class (1111)
            highNibble == 0x0F -> {
                encoding = if ((dcs and 0x04) != 0) DcsEncoding.BINARY else DcsEncoding.GSM7
                messageClass = dcs and 0x03
                if (messageClass == 2) {
                    threatType = "SIM_SPECIFIC_DCS"
                    level = ThreatLevel.HIGH
                    desc.append("SIM-specific data message (DCS Class 2). ")
                }
            }
        }

        // Binary message flag (8-bit data)
        if (encoding == DcsEncoding.BINARY && threatType == null) {
            desc.append("8-bit binary data message. ")
        }

        return DcsAnalysisResult(encoding, messageClass, threatType, level, desc.toString())
    }

    // ======================== UDH Analysis ========================

    private fun analyzeUdh(userData: ByteArray): String? {
        if (userData.isEmpty()) return null
        val udhLen = userData[0].toInt() and 0xFF
        if (udhLen == 0 || udhLen >= userData.size) return null

        var offset = 1
        while (offset < udhLen + 1 && offset + 1 < userData.size) {
            val iei = userData[offset].toInt() and 0xFF
            val ieLen = userData[offset + 1].toInt() and 0xFF
            offset += 2

            when (iei) {
                0x70 -> return "SIM_TOOLKIT_SECURITY"
                0x71 -> return "SMS_PP_DOWNLOAD"
                0x00, 0x08 -> { /* concatenated SMS — normal */ }
                0x01 -> return "SPECIAL_SMS_INDICATION"
                0x04 -> return "APPLICATION_PORT_8BIT"
                0x05 -> {
                    // 16-bit port addressing — check for SIMJacker port
                    if (offset + 3 < userData.size) {
                        val destPort = ((userData[offset].toInt() and 0xFF) shl 8) or (userData[offset + 1].toInt() and 0xFF)
                        when (destPort) {
                            SIMJACKER_PORT -> return "SIMJACKER_PORT"
                            2948 -> return "WAP_PUSH_PORT"
                            in 9200..9203 -> return "WAP_CONNECTION_PORT"
                        }
                    }
                    return "APPLICATION_PORT_16BIT"
                }
                0x06 -> return "SMSC_CONTROL"
                0x07 -> return "UDH_SOURCE_INDICATOR"
                0x09 -> return "WIRELESS_CTRL_MSG"
                0x24 -> return "NATIONAL_LANGUAGE_SINGLE_SHIFT"
                0x25 -> return "NATIONAL_LANGUAGE_LOCKING_SHIFT"
            }
            offset += ieLen
        }
        return null
    }

    /**
     * Extract destination/source ports from UDH.
     */
    private fun extractPorts(userData: ByteArray): PortInfo? {
        if (userData.isEmpty()) return null
        val udhLen = userData[0].toInt() and 0xFF
        if (udhLen == 0 || udhLen >= userData.size) return null

        var offset = 1
        while (offset < udhLen + 1 && offset + 1 < userData.size) {
            val iei = userData[offset].toInt() and 0xFF
            val ieLen = userData[offset + 1].toInt() and 0xFF
            offset += 2

            when (iei) {
                0x04 -> {
                    // 8-bit ports
                    if (offset + 1 < userData.size) {
                        return PortInfo(
                            destPort = userData[offset].toInt() and 0xFF,
                            srcPort = userData[offset + 1].toInt() and 0xFF,
                            is16bit = false
                        )
                    }
                }
                0x05 -> {
                    // 16-bit ports
                    if (offset + 3 < userData.size) {
                        return PortInfo(
                            destPort = ((userData[offset].toInt() and 0xFF) shl 8) or (userData[offset + 1].toInt() and 0xFF),
                            srcPort = ((userData[offset + 2].toInt() and 0xFF) shl 8) or (userData[offset + 3].toInt() and 0xFF),
                            is16bit = true
                        )
                    }
                }
            }
            offset += ieLen
        }
        return null
    }

    /**
     * Detect STK (SIM Toolkit) proactive commands in SMS payload.
     * Based on RILDefender proactive_sim_sms detection.
     */
    private fun detectStkCommands(userData: ByteArray): List<String> {
        val commands = mutableListOf<String>()
        if (userData.size < 3) return commands

        // Skip UDH header
        val udhLen = userData[0].toInt() and 0xFF
        val dataStart = udhLen + 1
        if (dataStart >= userData.size) return commands

        // Scan for BER-TLV encoded proactive commands
        var offset = dataStart
        while (offset < userData.size - 1) {
            val tag = userData[offset].toInt() and 0xFF
            // Proactive SIM command tag (0xD0)
            if (tag == 0xD0 && offset + 2 < userData.size) {
                val cmdLen = userData[offset + 1].toInt() and 0xFF
                if (offset + 2 + cmdLen <= userData.size) {
                    // Look for command details TLV (tag 0x01)
                    var innerOffset = offset + 2
                    while (innerOffset < offset + 2 + cmdLen - 1) {
                        val innerTag = userData[innerOffset].toInt() and 0xFF
                        if ((innerTag and 0x7F) == 0x01) {
                            val innerLen = userData[innerOffset + 1].toInt() and 0xFF
                            if (innerOffset + 2 < userData.size) {
                                val cmdType = userData[innerOffset + 2].toInt() and 0xFF
                                when (cmdType) {
                                    STK_SEND_SMS -> commands.add("STK_SEND_SMS")
                                    STK_RUN_AT_CMD -> commands.add("STK_RUN_AT_CMD")
                                    STK_SEND_USSD -> commands.add("STK_SEND_USSD")
                                    STK_SETUP_CALL -> commands.add("STK_SETUP_CALL")
                                    STK_SEND_SS -> commands.add("STK_SEND_SS")
                                    STK_SEND_DTMF -> commands.add("STK_SEND_DTMF")
                                    STK_LAUNCH_BROWSER -> commands.add("STK_LAUNCH_BROWSER")
                                    STK_PROVIDE_LOCAL_INFO -> commands.add("STK_PROVIDE_LOCAL_INFO")
                                    STK_OPEN_CHANNEL -> commands.add("STK_OPEN_CHANNEL")
                                    STK_CLOSE_CHANNEL -> commands.add("STK_CLOSE_CHANNEL")
                                    STK_SEND_DATA -> commands.add("STK_SEND_DATA")
                                    STK_RECEIVE_DATA -> commands.add("STK_RECEIVE_DATA")
                                    STK_DISPLAY_TEXT -> commands.add("STK_DISPLAY_TEXT")
                                    STK_POWER_OFF_CARD -> commands.add("STK_POWER_OFF_CARD")
                                    STK_POWER_ON_CARD -> commands.add("STK_POWER_ON_CARD")
                                    STK_GET_READER_STATUS -> commands.add("STK_GET_READER_STATUS")
                                }
                            }
                            innerOffset += 2 + innerLen
                        } else {
                            if (innerOffset + 1 < userData.size) {
                                innerOffset += 2 + (userData[innerOffset + 1].toInt() and 0xFF)
                            } else break
                        }
                    }
                }
            }
            offset++
        }
        return commands
    }

    // ======================== Pattern Matching Helpers ========================

    private fun containsPattern(data: ByteArray, pattern: ByteArray): Boolean {
        if (pattern.size > data.size) return false
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    data class ConcatenatedInfo(val refNum: Int, val totalParts: Int, val partNum: Int)

    private fun extractConcatenatedInfo(userData: ByteArray): ConcatenatedInfo? {
        if (userData.isEmpty()) return null
        val udhLen = userData[0].toInt() and 0xFF
        if (udhLen == 0 || udhLen >= userData.size) return null
        var offset = 1
        while (offset < udhLen + 1 && offset + 1 < userData.size) {
            val iei = userData[offset].toInt() and 0xFF
            val ieLen = userData[offset + 1].toInt() and 0xFF
            offset += 2
            when (iei) {
                0x00 -> {
                    if (offset + 2 < userData.size) {
                        return ConcatenatedInfo(
                            refNum = userData[offset].toInt() and 0xFF,
                            totalParts = userData[offset + 1].toInt() and 0xFF,
                            partNum = userData[offset + 2].toInt() and 0xFF
                        )
                    }
                }
                0x08 -> {
                    if (offset + 3 < userData.size) {
                        val ref = ((userData[offset].toInt() and 0xFF) shl 8) or
                                (userData[offset + 1].toInt() and 0xFF)
                        return ConcatenatedInfo(
                            refNum = ref,
                            totalParts = userData[offset + 2].toInt() and 0xFF,
                            partNum = userData[offset + 3].toInt() and 0xFF
                        )
                    }
                }
            }
            offset += ieLen
        }
        return null
    }

    // ======================== Codec Helpers (GSM 03.38) ========================

    /**
     * BCD address decoding (GSM 04.08 format).
     */
    private fun decodeBcdAddress(pdu: ByteArray, offset: Int, length: Int, addressType: Int): String {
        val sb = StringBuilder()
        if ((addressType and 0x70) == 0x10) sb.append("+")
        for (i in 0 until length) {
            if (offset + i >= pdu.size) break
            val b = pdu[offset + i].toInt() and 0xFF
            val lo = b and 0x0F
            val hi = (b shr 4) and 0x0F
            if (lo <= 9) sb.append(lo)
            if (hi <= 9) sb.append(hi)
        }
        return sb.toString()
    }

    /**
     * GSM 7-bit default alphabet decoder with extended table support (GSM 03.38 §6.2.1).
     * Reference: smspdudecoder GSM.decode()
     */
    fun decodeGsm7BitDefault(data: ByteArray, charCount: Int): String {
        val sb = StringBuilder()
        var bitPos = 0
        var isExtended = false
        val maxChars = minOf(charCount, data.size * 8 / 7)
        for (i in 0 until maxChars) {
            val byteOffset = (bitPos * 7) / 8
            val bitOffset = (bitPos * 7) % 8
            if (byteOffset >= data.size) break
            var charVal = (data[byteOffset].toInt() and 0xFF) shr bitOffset
            if (bitOffset > 1 && byteOffset + 1 < data.size) {
                charVal = charVal or ((data[byteOffset + 1].toInt() and 0xFF) shl (8 - bitOffset))
            }
            charVal = charVal and 0x7F

            if (isExtended) {
                isExtended = false
                val extChar = GSM7_EXTENDED[charVal]
                sb.append(extChar ?: ' ')
            } else if (charVal == 0x1B) {
                isExtended = true
            } else if (charVal < GSM7_DEFAULT.size) {
                sb.append(GSM7_DEFAULT[charVal])
            } else {
                sb.append('?')
            }
            bitPos++
        }
        return sb.toString()
    }

    /**
     * GSM 7-bit decoder with UDH offset (skips header septets).
     */
    private fun decodeGsm7BitWithOffset(data: ByteArray, charCount: Int, headerSeptets: Int): String {
        val sb = StringBuilder()
        var bitPos = 0
        var isExtended = false
        val totalBits = data.size * 8
        for (i in 0 until charCount) {
            val bitStart = i * 7
            if (bitStart + 7 > totalBits) break
            val byteOffset = bitStart / 8
            val bitOffset = bitStart % 8
            if (byteOffset >= data.size) break
            var charVal = (data[byteOffset].toInt() and 0xFF) shr bitOffset
            if (bitOffset > 1 && byteOffset + 1 < data.size) {
                charVal = charVal or ((data[byteOffset + 1].toInt() and 0xFF) shl (8 - bitOffset))
            }
            charVal = charVal and 0x7F

            if (i < headerSeptets) {
                bitPos++
                continue
            }

            if (isExtended) {
                isExtended = false
                val extChar = GSM7_EXTENDED[charVal]
                sb.append(extChar ?: ' ')
            } else if (charVal == 0x1B) {
                isExtended = true
            } else if (charVal < GSM7_DEFAULT.size) {
                sb.append(GSM7_DEFAULT[charVal])
            } else {
                sb.append('?')
            }
            bitPos++
        }
        return sb.toString()
    }

    /**
     * Decode packed GSM 7-bit from byte array at given offset.
     */
    private fun decodeGsm7BitPacked(pdu: ByteArray, offset: Int, byteLen: Int, charCount: Int): String {
        val data = pdu.copyOfRange(offset, minOf(offset + byteLen, pdu.size))
        return decodeGsm7BitDefault(data, charCount)
    }

    /**
     * UCS-2 (UTF-16BE) decoder.
     */
    private fun decodeUcs2(data: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder()
        var i = offset
        val end = minOf(offset + length, data.size)
        while (i + 1 < end) {
            val hi = (data[i].toInt() and 0xFF)
            val lo = (data[i + 1].toInt() and 0xFF)
            val codePoint = (hi shl 8) or lo
            sb.append(codePoint.toChar())
            i += 2
        }
        return sb.toString()
    }

    // ======================== WSP/WAP Helpers ========================

    private fun decodeUintvar(data: ByteArray, start: Int): Pair<Int, Int> {
        var value = 0
        var offset = start
        var bytesRead = 0
        while (offset < data.size && bytesRead < 5) {
            val b = data[offset].toInt() and 0xFF
            val shifted = value shl 7
            if (shifted < 0 || shifted or (b and 0x7F) < 0) {
                value = Int.MAX_VALUE
                offset++
                break
            }
            value = shifted or (b and 0x7F)
            offset++
            bytesRead++
            if ((b and 0x80) == 0) break
        }
        return Pair(value, offset)
    }

    private fun decodeWspContentType(code: Int): String = when (code) {
        0x03 -> "text/plain"
        0x04 -> "text/html"
        0x06 -> "text/vnd.wap.wml"
        0x07 -> "text/vnd.wap.wmlscript"
        0x22 -> "application/vnd.wap.multipart.mixed"
        0x23 -> "application/vnd.wap.multipart.form-data"
        0x24 -> "application/vnd.wap.multipart.byteranges"
        0x25 -> "application/vnd.wap.multipart.alternative"
        0x29 -> "application/xml"
        0x2E -> "application/vnd.wap.si"
        0x30 -> "application/vnd.wap.sl"
        0x31 -> "application/vnd.wap.co"
        0x33 -> "application/vnd.wap.connectivity-wbxml"
        0x34 -> "application/vnd.wap.multipart.related"
        0x3E -> "application/vnd.wap.mms-message"
        0x44 -> "application/vnd.oma.drm.message"
        0x46 -> "application/vnd.oma.drm.content"
        0x47 -> "application/vnd.oma.drm.rights+xml"
        0x48 -> "application/vnd.oma.drm.rights+wbxml"
        else -> "0x${"%02x".format(code)}"
    }

    private fun contentTypeContainsSi(data: ByteArray, offset: Int): Boolean {
        if (offset + 3 >= data.size) return false
        return (data[offset].toInt() and 0xFF) == 0x2E
    }

    private fun contentTypeContainsSl(data: ByteArray, offset: Int): Boolean {
        if (offset + 3 >= data.size) return false
        return (data[offset].toInt() and 0xFF) == 0x30
    }

    // ==================== BODY CONTENT ANALYSIS ====================

    /**
     * Analyzes SMS body text for phishing, malware URLs, AT commands,
     * hex-encoded payloads, and social engineering patterns.
     * Returns list of (attackName, description, threatLevel) triples.
     */
    private fun analyzeBodyContent(body: String, sender: String): List<Triple<String, String, ThreatLevel>> {
        val results = mutableListOf<Triple<String, String, ThreatLevel>>()
        val lower = body.lowercase()

        // Phishing URL patterns
        val urlRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        val urls = urlRegex.findAll(body).map { it.value }.toList()
        for (url in urls) {
            val urlLower = url.lowercase()
            // IP-based URLs
            if (Regex("https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(url)) {
                results.add(Triple("PHISHING_IP_URL", "SMS contains IP-based URL: suspicious link.", ThreatLevel.MEDIUM))
            }
            // Disposable/free TLD domains
            if (Regex("\\.(tk|ml|ga|cf|gq|xyz|top|buzz|click|loan|work|date|racing|win|bid)/", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                results.add(Triple("PHISHING_FREE_TLD", "SMS contains link with disposable TLD.", ThreatLevel.HIGH))
            }
            // URL shorteners
            if (urlLower.contains("bit.ly") || urlLower.contains("tinyurl") || urlLower.contains("t.co/") ||
                urlLower.contains("goo.gl") || urlLower.contains("is.gd") || urlLower.contains("v.gd")) {
                results.add(Triple("URL_SHORTENER", "SMS contains shortened URL (link destination hidden).", ThreatLevel.LOW))
            }
            // APK/executable download links
            if (Regex("\\.(apk|dex|exe|msi|bat|cmd|sh|bin|elf)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                results.add(Triple("MALWARE_URL", "SMS contains link to executable file.", ThreatLevel.CRITICAL))
            }
        }

        // AT command injection in body
        if (Regex("AT\\+[A-Z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            results.add(Triple("AT_COMMAND_INJECTION", "SMS body contains AT modem commands.", ThreatLevel.HIGH))
        }

        // Hex-encoded payload (long hex strings that could be binary commands)
        if (Regex("[0-9a-fA-F]{32,}").containsMatchIn(body) && body.length < 200) {
            results.add(Triple("HEX_PAYLOAD", "SMS contains long hex-encoded data (possible binary command).", ThreatLevel.MEDIUM))
        }

        // Base64 encoded payload
        if (Regex("[A-Za-z0-9+/]{40,}={0,2}").containsMatchIn(body) && !body.contains(" ")) {
            results.add(Triple("BASE64_PAYLOAD", "SMS contains base64-encoded payload.", ThreatLevel.MEDIUM))
        }

        // Social engineering: urgent financial/password requests from short numbers
        if (sender.length <= 6 && !sender.startsWith("+")) {
            if (lower.contains("пароль") || lower.contains("password") || lower.contains("пін") ||
                lower.contains("pin") || lower.contains("cvv") || lower.contains("код") ||
                lower.contains("otp") || lower.contains("підтвердження") || lower.contains("подтверждение")) {
                results.add(Triple("CREDENTIAL_PHISHING",
                    "Short-number SMS requesting credentials — possible phishing.", ThreatLevel.MEDIUM))
            }
        }

        // JavaScript injection attempt
        if (lower.contains("<script") || lower.contains("javascript:") || lower.contains("onerror=") ||
            lower.contains("onload=")) {
            results.add(Triple("XSS_INJECTION", "SMS contains script injection attempt.", ThreatLevel.HIGH))
        }

        return results
    }

    // ==================== TIMESTAMP ANOMALY DETECTION ====================

    /**
     * Analyzes SCTS (Service Centre Time Stamp) for anomalies.
     * SCTS format: yy-MM-dd-HH-mm-ss-tz (BCD encoded, 7 bytes)
     */
    private fun analyzeScts(scts: ByteArray): Triple<String, ThreatLevel, String>? {
        try {
            val year = decodeBcdByte(scts[0]) + 2000
            val month = decodeBcdByte(scts[1])
            val day = decodeBcdByte(scts[2])
            val hour = decodeBcdByte(scts[3])
            val minute = decodeBcdByte(scts[4])

            val now = java.util.Calendar.getInstance()
            val currentYear = now.get(java.util.Calendar.YEAR)

            // Future timestamp (more than 1 day ahead)
            if (year > currentYear + 1) {
                return Triple("FUTURE_TIMESTAMP", ThreatLevel.MEDIUM,
                    "SMS timestamp is in the far future ($year-$month-$day) — possible FBS clock spoof.")
            }

            // Very old timestamp (more than 2 years old)
            if (year < currentYear - 2) {
                return Triple("OLD_TIMESTAMP", ThreatLevel.LOW,
                    "SMS timestamp is very old ($year-$month-$day) — delayed/replayed message.")
            }

            // Invalid date values
            if (month > 12 || month == 0 || day > 31 || day == 0 || hour > 23 || minute > 59) {
                return Triple("INVALID_TIMESTAMP", ThreatLevel.HIGH,
                    "Invalid SMS timestamp ($year-$month-$day $hour:$minute) — corrupted PDU or exploit.")
            }
        } catch (e: Exception) {
            return Triple("TIMESTAMP_PARSE_ERROR", ThreatLevel.LOW,
                "Cannot parse SMS timestamp — malformed SCTS field.")
        }
        return null
    }

    private fun decodeBcdByte(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v and 0x0F) * 10 + ((v shr 4) and 0x0F)
    }

    // ==================== WAP PUSH / OTA DECODER ====================

    private fun decodeWapPushPayload(userData: ByteArray, hasUdhi: Boolean): String? {
        try {
            val dataStart = if (hasUdhi) ((userData[0].toInt() and 0xFF) + 1).coerceAtMost(userData.size) else 0
            if (dataStart >= userData.size - 2) return null
            val payload = userData.copyOfRange(dataStart, userData.size)

            // WAP Push Content-Type identifiers
            val contentType = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: return null

            val typeStr = when (contentType) {
                0x06 -> "text/plain"
                0x30 -> "application/vnd.wap.sic" // SI Push
                0x31 -> "application/vnd.wap.slc" // SL Push (Service Loading)
                0x32 -> "application/vnd.wap.coc" // CO Push
                0x3E -> "application/vnd.wap.connectivity-wbxml" // OTA provisioning
                0x44 -> "application/vnd.oma.drm.rights+xml"
                0x46 -> "application/vnd.oma.drm.rights+wbxml"
                0xB0 -> "application/vnd.wap.locc+wbxml" // Location Push
                0xB4 -> "application/vnd.syncml.dm+wbxml" // OMA DM
                else -> "unknown/0x${"%02x".format(contentType)}"
            }

            // OTA provisioning (0x3E) is highly suspicious
            if (contentType == 0x3E || contentType == 0xB4) {
                return "OTA provisioning ($typeStr) — remote device configuration attempt"
            }

            // Service Loading (SL) can auto-load URLs
            if (contentType == 0x31) {
                return "Service Loading ($typeStr) — auto-loading URL push"
            }

            return "WAP Push type: $typeStr"
        } catch (e: Exception) {
            return null
        }
    }

    // ==================== STK COMMAND DETECTION ====================

    private fun detectStkCommands(userData: ByteArray, hasUdhi: Boolean): String? {
        val dataStart = if (hasUdhi) ((userData[0].toInt() and 0xFF) + 1).coerceAtMost(userData.size) else 0
        if (dataStart >= userData.size - 3) return null
        val payload = userData.copyOfRange(dataStart, userData.size)

        // Look for STK proactive command tags
        for (i in 0 until payload.size - 1) {
            val b = payload[i].toInt() and 0xFF
            if (b == 0xD0 || b == 0xD1) { // BER-TLV proactive command / SMS-PP download
                val cmdByte = payload.getOrNull(i + 3)?.toInt()?.and(0xFF) ?: continue
                val cmdName = when (cmdByte) {
                    STK_SETUP_CALL -> "SETUP_CALL (dial number)"
                    STK_SEND_SS -> "SEND_SS (supplementary service)"
                    STK_SEND_USSD -> "SEND_USSD"
                    STK_SEND_SMS -> "SEND_SMS (exfiltrate data)"
                    STK_SEND_DTMF -> "SEND_DTMF"
                    STK_LAUNCH_BROWSER -> "LAUNCH_BROWSER (phishing URL)"
                    STK_PROVIDE_LOCAL_INFO -> "PROVIDE_LOCAL_INFO (location leak)"
                    STK_OPEN_CHANNEL -> "OPEN_CHANNEL (data exfil channel)"
                    STK_CLOSE_CHANNEL -> "CLOSE_CHANNEL"
                    STK_SEND_DATA -> "SEND_DATA (exfiltrate)"
                    STK_RUN_AT_CMD -> "RUN_AT_COMMAND (modem control)"
                    STK_POWER_OFF_CARD -> "POWER_OFF_CARD (DoS)"
                    STK_POWER_ON_CARD -> "POWER_ON_CARD"
                    STK_RECEIVE_DATA -> "RECEIVE_DATA"
                    STK_DISPLAY_TEXT -> "DISPLAY_TEXT (social engineering)"
                    else -> null
                }
                if (cmdName != null) return cmdName
            }
        }
        return null
    }

    // ==================== SIMJACKER EXPLOIT DETECTION ====================

    private fun detectSimjackerExploit(userData: ByteArray, hasUdhi: Boolean): String? {
        val dataStart = if (hasUdhi) ((userData[0].toInt() and 0xFF) + 1).coerceAtMost(userData.size) else 0
        if (dataStart >= userData.size - 4) return null
        val payload = userData.copyOfRange(dataStart, userData.size)

        // S@T Browser command header: 0xA0 0xA4 (SELECT) or 0xA0 0xC0 (GET RESPONSE)
        for (i in 0 until payload.size - 3) {
            val b0 = payload[i].toInt() and 0xFF
            val b1 = payload.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: continue

            // S@T Browser APDU commands
            if (b0 == 0xA0) {
                when (b1) {
                    0xA4 -> return "S@T SELECT command — file system traversal"
                    0xC0 -> return "S@T GET RESPONSE — data exfiltration"
                    0xB0 -> return "S@T READ BINARY — reading SIM data"
                    0xB2 -> return "S@T READ RECORD — reading SIM records"
                    0xF2 -> return "S@T STATUS — querying SIM status"
                    0x12 -> return "S@T FETCH — retrieving proactive command"
                }
            }

            // Envelope command for STK
            if (b0 == 0x80 && b1 == 0xC2) {
                return "STK ENVELOPE command — remote command injection"
            }
        }

        // Check for BER-TLV S@T Browser header
        if (payload.size >= 3 && (payload[0].toInt() and 0xFF) == 0xD0) {
            val innerTag = payload.getOrNull(2)?.toInt()?.and(0xFF) ?: return null
            if (innerTag == 0x81) {
                return "S@T BER-TLV proactive command header detected"
            }
        }

        return null
    }

    // ==================== WIBATTACK DETECTION ====================

    private fun detectWibAttack(userData: ByteArray, hasUdhi: Boolean): String? {
        val dataStart = if (hasUdhi) ((userData[0].toInt() and 0xFF) + 1).coerceAtMost(userData.size) else 0
        if (dataStart >= userData.size - 2) return null
        val payload = userData.copyOfRange(dataStart, userData.size)

        // WIB (Wireless Internet Browser) commands
        for (i in 0 until payload.size - 2) {
            val b = payload[i].toInt() and 0xFF
            // WIB uses WML (Wireless Markup Language) bytecodes
            if (b == 0x01) { // WBXML version indicator
                val pubId = payload.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: continue
                if (pubId == 0x0D || pubId == 0x04) { // WML 1.1 / WML 1.3
                    // Check for navigate/go tags that could redirect to malicious URLs
                    for (j in i until (payload.size - 1).coerceAtMost(i + 50)) {
                        val tag = payload[j].toInt() and 0xFF
                        if (tag == 0x83) return "WIB navigate/go command — URL redirection"
                        if (tag == 0x8E) return "WIB postfield — data exfiltration form"
                    }
                    return "WIB WML binary payload detected"
                }
            }
        }
        return null
    }

    // ==================== PCAP EXPORT ====================

    fun exportPduToPcap(pduList: List<ByteArray>, outputPath: String): Boolean {
        return try {
            java.io.FileOutputStream(outputPath).use { fos ->
                // PCAP Global Header (24 bytes)
                val globalHeader = byteArrayOf(
                    0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(), // Magic number (little-endian)
                    0x02, 0x00, // Major version
                    0x04, 0x00, // Minor version
                    0x00, 0x00, 0x00, 0x00, // Timezone offset
                    0x00, 0x00, 0x00, 0x00, // Timestamp accuracy
                    0x00, 0x00, 0x04, 0x00, // Snap length (65536)
                    0x93.toByte(), 0x00, 0x00, 0x00 // Link-layer type: GSMTAP (147)
                )
                fos.write(globalHeader)

                for (pdu in pduList) {
                    val now = System.currentTimeMillis()
                    val tsSec = (now / 1000).toInt()
                    val tsUsec = ((now % 1000) * 1000).toInt()

                    // GSMTAP header (16 bytes) + PDU
                    val gsmtapHeader = byteArrayOf(
                        0x02, // Version 2
                        0x04, // Header length (16 bytes / 4 = 4 words)
                        0x04, // Type: UM (SMS)
                        0x00, // Timeslot
                        0x00, 0x00, // ARFCN
                        0x00, // Signal dBm
                        0x00, // SNR dB
                        0x00, 0x00, 0x00, 0x00, // Frame number
                        0x04, // Sub-type: SMS
                        0x00, // Antenna number
                        0x00, 0x00 // Sub-slot
                    )

                    val packetLen = gsmtapHeader.size + pdu.size

                    // PCAP Record Header (16 bytes)
                    writePcapInt32(fos, tsSec)
                    writePcapInt32(fos, tsUsec)
                    writePcapInt32(fos, packetLen)
                    writePcapInt32(fos, packetLen)

                    fos.write(gsmtapHeader)
                    fos.write(pdu)
                }
            }
            Log.i(TAG, "PCAP export: ${pduList.size} PDUs → $outputPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PCAP export failed: ${e.message}")
            false
        }
    }

    private fun writePcapInt32(fos: java.io.FileOutputStream, value: Int) {
        fos.write(value and 0xFF)
        fos.write((value shr 8) and 0xFF)
        fos.write((value shr 16) and 0xFF)
        fos.write((value shr 24) and 0xFF)
    }
}
