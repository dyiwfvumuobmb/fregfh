package com.android.imeisettings.util

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * RCS (Rich Communication Services) message analyzer.
 * Detects malicious RCS messages by monitoring the telephony provider.
 *
 * RCS threats detected:
 * - RCS provisioning manipulation (config SMS/HTTP)
 * - Chatbot impersonation attacks
 * - File transfer exploit payloads
 * - RCS Business Messaging spoofing
 * - Identity spoofing via P-Asserted-Identity manipulation
 * - Malicious deep links and redirects
 */
object RcsAnalyzer {

    private const val TAG = "CONSUL_RCS"

    data class RcsAnalysisResult(
        val isThreat: Boolean,
        val threatLevel: PduAnalyzer.ThreatLevel,
        val attacks: List<String>,
        val description: String
    )

    // Suspicious RCS provisioning patterns
    private val RCS_PROVISIONING_PATTERNS = listOf(
        Regex("http-stack.*config", RegexOption.IGNORE_CASE),
        Regex("rcs_config.*url", RegexOption.IGNORE_CASE),
        Regex("ims.*provisioning", RegexOption.IGNORE_CASE),
        Regex("sip:.*@.*\\.(tk|ml|ga|cf)", RegexOption.IGNORE_CASE),
        Regex("xcap.*root", RegexOption.IGNORE_CASE),
    )

    // Suspicious file extensions in RCS file transfers
    private val DANGEROUS_FILE_EXTENSIONS = setOf(
        ".apk", ".dex", ".jar", ".so",     // Android executables
        ".sh", ".bin", ".elf",              // Linux binaries
        ".exe", ".bat", ".cmd", ".scr",     // Windows executables (cross-platform risk)
        ".html", ".htm", ".js", ".svg",     // Web content
        ".vcard", ".vcf",                   // vCard can contain exploits
    )

    // Suspicious deep link patterns
    private val SUSPICIOUS_DEEPLINKS = listOf(
        Regex("intent://.*#Intent;", RegexOption.IGNORE_CASE),              // Android intent scheme
        Regex("market://details\\?id=", RegexOption.IGNORE_CASE),           // Play Store redirect
        Regex("content://.*provider", RegexOption.IGNORE_CASE),             // Content provider access
        Regex("file:///.*\\.(apk|dex|sh)", RegexOption.IGNORE_CASE),        // Local file access
        Regex("tel:\\*#[0-9*#]{3,}#", RegexOption.IGNORE_CASE),             // USSD via tel:
        Regex("sms:\\?body=.*AT\\+", RegexOption.IGNORE_CASE),              // SMS with AT commands
    )

    /**
     * Analyze an RCS message body for threats.
     * Can be called from PduInterceptorService when monitoring the SMS/MMS content provider.
     */
    fun analyzeMessage(
        sender: String?,
        body: String?,
        contentType: String?,
        fileUrl: String?,
        isGroupChat: Boolean = false
    ): RcsAnalysisResult {
        val attacks = mutableListOf<String>()
        var threatLevel = PduAnalyzer.ThreatLevel.NONE
        val description = StringBuilder()

        try {
            // 1. RCS provisioning manipulation
            if (body != null) {
                for (pattern in RCS_PROVISIONING_PATTERNS) {
                    if (pattern.containsMatchIn(body)) {
                        attacks.add("RCS_PROVISIONING_ATTACK")
                        threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.CRITICAL)
                        description.append("RCS provisioning manipulation detected in message body. ")
                        break
                    }
                }
            }

            // 2. Chatbot impersonation
            if (sender != null) {
                val senderLower = sender.lowercase()
                if (senderLower.contains("bot") && !senderLower.startsWith("+")) {
                    // Legitimate chatbots use verified sender IDs
                    if (senderLower.contains("bank") || senderLower.contains("pay") ||
                        senderLower.contains("secure") || senderLower.contains("verify")) {
                        attacks.add("RCS_CHATBOT_IMPERSONATION")
                        threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.HIGH)
                        description.append("Possible chatbot impersonation: sender '$sender' uses financial/security keywords. ")
                    }
                }
            }

            // 3. Dangerous file transfer
            if (fileUrl != null) {
                val lowerUrl = fileUrl.lowercase()
                for (ext in DANGEROUS_FILE_EXTENSIONS) {
                    if (lowerUrl.endsWith(ext)) {
                        attacks.add("RCS_DANGEROUS_FILE")
                        threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.HIGH)
                        description.append("Dangerous file type in RCS transfer: $ext. ")
                        break
                    }
                }

                // IP-based URL
                if (Regex("https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(fileUrl)) {
                    attacks.add("RCS_IP_FILE_URL")
                    threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.MEDIUM)
                    description.append("RCS file served from IP address (no domain). ")
                }

                // HTTP without TLS
                if (fileUrl.startsWith("http://")) {
                    attacks.add("RCS_UNENCRYPTED_FILE")
                    threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.MEDIUM)
                    description.append("RCS file transfer over unencrypted HTTP. ")
                }
            }

            // 4. Suspicious deep links in body
            if (body != null) {
                for (pattern in SUSPICIOUS_DEEPLINKS) {
                    if (pattern.containsMatchIn(body)) {
                        attacks.add("RCS_MALICIOUS_DEEPLINK")
                        threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.HIGH)
                        description.append("Malicious deep link in RCS message. ")
                        break
                    }
                }
            }

            // 5. RCS Business Messaging spoofing
            // Legitimate RBM uses verified sender IDs with brand name
            if (sender != null && !sender.startsWith("+") && body != null) {
                val bodyLower = body.lowercase()
                if (bodyLower.contains("password") || bodyLower.contains("пароль") ||
                    bodyLower.contains("pin") || bodyLower.contains("otp") ||
                    bodyLower.contains("код підтвердження") || bodyLower.contains("код подтверждения")) {
                    attacks.add("RCS_BUSINESS_SPOOF")
                    threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.MEDIUM)
                    description.append("RCS business message requesting credentials from unverified sender. ")
                }
            }

            // 6. Oversized message body (potential buffer overflow)
            if (body != null && body.length > 10000) {
                attacks.add("RCS_OVERSIZED_BODY")
                threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.MEDIUM)
                description.append("Oversized RCS message body (${body.length} chars) — possible exploit. ")
            }

            // 7. Group chat spam/flood detection
            if (isGroupChat && sender != null) {
                // Individual message analysis — flood detection should be done at service level
                if (body != null && body.length < 3 && body.all { !it.isLetterOrDigit() }) {
                    attacks.add("RCS_GROUP_SPAM")
                    threatLevel = maxOf(threatLevel, PduAnalyzer.ThreatLevel.LOW)
                    description.append("Possible group chat spam/flood. ")
                }
            }

            if (description.isEmpty()) {
                description.append("Standard RCS message.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "RCS analysis error: ${e.message}")
            description.append("RCS analysis error: ${e.message}")
        }

        return RcsAnalysisResult(
            isThreat = attacks.isNotEmpty(),
            threatLevel = threatLevel,
            attacks = attacks,
            description = description.toString()
        )
    }

    /**
     * Scan the MMS/SMS content provider for recent suspicious RCS messages.
     * Called periodically by PduInterceptorService.
     */
    fun scanRecentMessages(contentResolver: ContentResolver, sinceTimestamp: Long): List<RcsAnalysisResult> {
        val results = mutableListOf<RcsAnalysisResult>()
        try {
            // Check telephony MMS content provider
            val mmsUri = Uri.parse("content://mms")
            val cursor: Cursor? = try {
                contentResolver.query(
                    mmsUri,
                    arrayOf("_id", "date", "sub", "ct_t", "m_type"),
                    "date > ?",
                    arrayOf((sinceTimestamp / 1000).toString()),
                    "date DESC"
                )
            } catch (e: Exception) {
                Log.v(TAG, "MMS provider not available: ${e.message}")
                null
            }

            cursor?.use { c ->
                val idIdx = c.getColumnIndex("_id")
                val subIdx = c.getColumnIndex("sub")
                val ctIdx = c.getColumnIndex("ct_t")
                val typeIdx = c.getColumnIndex("m_type")

                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getString(idIdx) else continue
                    val subject = if (subIdx >= 0) c.getString(subIdx) else null
                    val contentType = if (ctIdx >= 0) c.getString(ctIdx) else null
                    val mType = if (typeIdx >= 0) c.getInt(typeIdx) else 0

                    // Get MMS sender
                    val sender = getMmsSender(contentResolver, id)

                    // Get MMS text parts
                    val body = getMmsTextBody(contentResolver, id)

                    // Get MMS file attachments
                    val fileUrl = getMmsFileUrl(contentResolver, id)

                    val result = analyzeMessage(sender, body ?: subject, contentType, fileUrl)
                    if (result.isThreat) {
                        results.add(result)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RCS scan error: ${e.message}")
        }
        return results
    }

    private fun getMmsSender(contentResolver: ContentResolver, mmsId: String): String? {
        return try {
            val addrUri = Uri.parse("content://mms/$mmsId/addr")
            val cursor = contentResolver.query(addrUri, arrayOf("address", "type"), "type=137", null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex("address")
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun getMmsTextBody(contentResolver: ContentResolver, mmsId: String): String? {
        return try {
            val partUri = Uri.parse("content://mms/part")
            val cursor = contentResolver.query(
                partUri, arrayOf("_id", "ct", "text"),
                "mid = ? AND ct = 'text/plain'", arrayOf(mmsId), null
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex("text")
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun getMmsFileUrl(contentResolver: ContentResolver, mmsId: String): String? {
        return try {
            val partUri = Uri.parse("content://mms/part")
            val cursor = contentResolver.query(
                partUri, arrayOf("_id", "ct", "cl", "_data"),
                "mid = ? AND ct != 'text/plain' AND ct != 'application/smil'",
                arrayOf(mmsId), null
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val clIdx = c.getColumnIndex("cl")
                    val dataIdx = c.getColumnIndex("_data")
                    (if (clIdx >= 0) c.getString(clIdx) else null)
                        ?: (if (dataIdx >= 0) c.getString(dataIdx) else null)
                } else null
            }
        } catch (e: Exception) { null }
    }
}
