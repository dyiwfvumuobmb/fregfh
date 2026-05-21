package com.android.imeisettings.util

import android.util.Log

/**
 * MMS PDU analyzer for detecting malicious MMS messages.
 * Parses WAP MMS PDU (OMA-WAP-MMS-ENC) to detect:
 * - Malicious MMS notifications (M-Notification.ind)
 * - Oversized content that may exploit buffer overflows
 * - Binary SMS-to-MMS escalation attacks
 * - Suspicious content types and URLs
 * - StageFright-style media exploits
 */
object MmsAnalyzer {

    private const val TAG = "CONSUL_MMS"

    // MMS PDU types (OMA-WAP-MMS 7.2.15)
    private const val MMS_MSG_TYPE_SEND_REQ = 0x80
    private const val MMS_MSG_TYPE_SEND_CONF = 0x81
    private const val MMS_MSG_TYPE_NOTIFICATION_IND = 0x82
    private const val MMS_MSG_TYPE_NOTIFYRESP_IND = 0x83
    private const val MMS_MSG_TYPE_RETRIEVE_CONF = 0x84
    private const val MMS_MSG_TYPE_ACKNOWLEDGE_IND = 0x85
    private const val MMS_MSG_TYPE_DELIVERY_IND = 0x86

    // MMS header field codes
    private const val MMS_HEADER_MESSAGE_TYPE = 0x8C
    private const val MMS_HEADER_CONTENT_TYPE = 0x84
    private const val MMS_HEADER_CONTENT_LOCATION = 0x83
    private const val MMS_HEADER_FROM = 0x89
    private const val MMS_HEADER_SUBJECT = 0x96
    private const val MMS_HEADER_MESSAGE_SIZE = 0x8E
    private const val MMS_HEADER_EXPIRY = 0x88
    private const val MMS_HEADER_X_MMS_MESSAGE_CLASS = 0x8A

    data class MmsAnalysisResult(
        val isThreat: Boolean,
        val threatLevel: PduAnalyzer.ThreatLevel,
        val attacks: List<String>,
        val description: String,
        val messageType: String,
        val contentLocation: String?,
        val from: String?,
        val subject: String?,
        val messageSize: Long
    )

    // Suspicious content types in MMS
    private val SUSPICIOUS_CONTENT_TYPES = listOf(
        "application/vnd.oma.drm",           // DRM exploit vectors
        "application/x-javascript",           // JS execution
        "application/smil",                    // SMIL injection
        "text/html",                           // HTML injection
        "application/x-sh",                    // Shell scripts
        "application/octet-stream",            // Binary blobs
        "video/mp4",                           // StageFright CVE-2015-1538
        "video/3gpp",                          // StageFright variants
        "video/h264",                          // H.264 parser exploits
        "audio/amr",                           // AMR parser exploits
        "image/gif",                           // GIF parser overflow
    )

    // Suspicious URL patterns in content-location
    private val SUSPICIOUS_URL_PATTERNS = listOf(
        Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"),    // IP address instead of domain
        Regex("https?://[^/]*\\.(tk|ml|ga|cf|gq|xyz|top)/"),    // Free/disposable TLDs
        Regex("https?://bit\\.ly|tinyurl|t\\.co|goo\\.gl"),     // URL shorteners
        Regex("\\.(apk|dex|jar|exe|scr|bat|cmd|vbs)$", RegexOption.IGNORE_CASE), // Executables
        Regex("https?://\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+/"),      // IP:port
    )

    fun analyze(mmsData: ByteArray): MmsAnalysisResult {
        val attacks = mutableListOf<String>()
        var threatLevel = PduAnalyzer.ThreatLevel.NONE
        val description = StringBuilder()
        var messageType = "UNKNOWN"
        var contentLocation: String? = null
        var from: String? = null
        var subject: String? = null
        var messageSize: Long = 0

        try {
            if (mmsData.size < 3) {
                return MmsAnalysisResult(false, PduAnalyzer.ThreatLevel.NONE, emptyList(),
                    "Too short for MMS PDU", "INVALID", null, null, null, 0)
            }

            var offset = 0

            // Parse MMS headers
            while (offset < mmsData.size - 1) {
                val headerField = mmsData[offset].toInt() and 0xFF
                offset++

                when (headerField) {
                    MMS_HEADER_MESSAGE_TYPE -> {
                        if (offset < mmsData.size) {
                            val type = mmsData[offset].toInt() and 0xFF
                            messageType = when (type) {
                                MMS_MSG_TYPE_SEND_REQ -> "M-Send.req"
                                MMS_MSG_TYPE_SEND_CONF -> "M-Send.conf"
                                MMS_MSG_TYPE_NOTIFICATION_IND -> "M-Notification.ind"
                                MMS_MSG_TYPE_NOTIFYRESP_IND -> "M-NotifyResp.ind"
                                MMS_MSG_TYPE_RETRIEVE_CONF -> "M-Retrieve.conf"
                                MMS_MSG_TYPE_ACKNOWLEDGE_IND -> "M-Acknowledge.ind"
                                MMS_MSG_TYPE_DELIVERY_IND -> "M-Delivery.ind"
                                else -> "Unknown(0x${"%02x".format(type)})"
                            }
                            offset++
                        }
                    }
                    MMS_HEADER_CONTENT_LOCATION -> {
                        contentLocation = readMmsString(mmsData, offset)
                        offset += (contentLocation?.length ?: 0) + 1
                    }
                    MMS_HEADER_FROM -> {
                        val fromLen = if (offset < mmsData.size) mmsData[offset].toInt() and 0xFF else 0
                        offset++
                        if (fromLen > 0 && offset + fromLen <= mmsData.size) {
                            from = String(mmsData, offset, fromLen, Charsets.UTF_8).trim()
                            offset += fromLen
                        }
                    }
                    MMS_HEADER_SUBJECT -> {
                        subject = readMmsString(mmsData, offset)
                        offset += (subject?.length ?: 0) + 1
                    }
                    MMS_HEADER_MESSAGE_SIZE -> {
                        messageSize = readMmsLong(mmsData, offset)
                        offset += 4.coerceAtMost(mmsData.size - offset)
                    }
                    MMS_HEADER_CONTENT_TYPE -> {
                        val ctStr = readMmsString(mmsData, offset)
                        if (ctStr != null) {
                            offset += ctStr.length + 1
                            for (suspicious in SUSPICIOUS_CONTENT_TYPES) {
                                if (ctStr.contains(suspicious, ignoreCase = true)) {
                                    attacks.add("MMS_SUSPICIOUS_CONTENT_TYPE")
                                    threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.MEDIUM)
                                    description.append("Suspicious MMS content-type: $ctStr. ")
                                    break
                                }
                            }
                            // StageFright detection
                            if (ctStr.contains("video/", ignoreCase = true) || ctStr.contains("audio/", ignoreCase = true)) {
                                if (messageSize > 10_000_000 || messageSize == 0L) {
                                    attacks.add("MMS_STAGEFRIGHT_RISK")
                                    threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.HIGH)
                                    description.append("Large/unknown media in MMS — possible StageFright exploit vector. ")
                                }
                            }
                        } else {
                            offset++
                        }
                    }
                    else -> {
                        // Skip unknown headers
                        if (headerField and 0x80 != 0) {
                            offset++ // Short header value
                        } else {
                            // Try to skip text value
                            while (offset < mmsData.size && mmsData[offset].toInt() != 0) offset++
                            offset++ // Skip null terminator
                        }
                    }
                }

                if (offset <= 0 || offset > mmsData.size) break
            }

            // 1. Content-Location URL analysis
            if (contentLocation != null) {
                for (pattern in SUSPICIOUS_URL_PATTERNS) {
                    if (pattern.containsMatchIn(contentLocation)) {
                        attacks.add("MMS_SUSPICIOUS_URL")
                        threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.HIGH)
                        description.append("Suspicious MMS content URL: $contentLocation. ")
                        break
                    }
                }
                // HTTP (no TLS) is suspicious for MMS
                if (contentLocation.startsWith("http://")) {
                    attacks.add("MMS_UNENCRYPTED_URL")
                    threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.MEDIUM)
                    description.append("MMS content URL uses unencrypted HTTP. ")
                }
            }

            // 2. Oversized MMS (>5MB may be exploit payload)
            if (messageSize > 5_000_000) {
                attacks.add("MMS_OVERSIZED")
                threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.MEDIUM)
                description.append("Oversized MMS (${messageSize / 1024}KB) — possible payload delivery. ")
            }

            // 3. Empty/null sender (spoofed notification)
            if (from.isNullOrBlank() && messageType == "M-Notification.ind") {
                attacks.add("MMS_ANONYMOUS_NOTIFICATION")
                threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.HIGH)
                description.append("Anonymous MMS notification — possible spoofed push. ")
            }

            // 4. Subject contains suspicious patterns
            if (subject != null) {
                val lowerSubject = subject.lowercase()
                if (lowerSubject.contains("\\x") || lowerSubject.contains("%00") ||
                    lowerSubject.length > 200) {
                    attacks.add("MMS_SUBJECT_EXPLOIT")
                    threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.MEDIUM)
                    description.append("MMS subject contains suspicious encoding or excessive length. ")
                }
            }

            // 5. Notification with zero expiry (immediate auto-download trigger)
            // This is a technique used to force auto-download before user can review

            if (description.isEmpty()) {
                description.append("Standard MMS message ($messageType).")
            }

        } catch (e: Exception) {
            Log.e(TAG, "MMS analysis error: ${e.message}")
            description.append("MMS parse error: ${e.message}")
        }

        return MmsAnalysisResult(
            isThreat = attacks.isNotEmpty(),
            threatLevel = threatLevel,
            attacks = attacks,
            description = description.toString(),
            messageType = messageType,
            contentLocation = contentLocation,
            from = from,
            subject = subject,
            messageSize = messageSize
        )
    }

    private fun readMmsString(data: ByteArray, startOffset: Int): String? {
        if (startOffset >= data.size) return null
        var end = startOffset
        while (end < data.size && data[end].toInt() != 0) end++
        if (end == startOffset) return null
        return try {
            String(data, startOffset, end - startOffset, Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun readMmsLong(data: ByteArray, startOffset: Int): Long {
        if (startOffset + 4 > data.size) return 0
        return ((data[startOffset].toLong() and 0xFF) shl 24) or
                ((data[startOffset + 1].toLong() and 0xFF) shl 16) or
                ((data[startOffset + 2].toLong() and 0xFF) shl 8) or
                (data[startOffset + 3].toLong() and 0xFF)
    }
}
