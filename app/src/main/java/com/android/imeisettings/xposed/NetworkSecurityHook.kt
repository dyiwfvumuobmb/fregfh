package com.android.imeisettings.xposed

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcel
import android.os.UserHandle
import android.telephony.SmsMessage
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File

class NetworkSecurityHook : IXposedHookLoadPackage {

    companion object {
        private const val ACTION_EVENT = "com.android.imeisettings.FORENSIC_EVENT"
        private const val TAG = "CONSUL_HOOK"
        private const val RIL_UNSOL_CIPHERING_INFO = 1042
        private const val RSSI_THRESHOLD_FBS = -45 // RILDefender threshold for proximity
        private const val PREFS_NAME = "consul_imei_xposed"
        private const val PREF_SPOOFED_IMEI_1 = "spoofed_imei_1"
        private const val PREF_SPOOFED_IMEI_2 = "spoofed_imei_2"
        private const val PREF_SPOOFED_MEID = "spoofed_meid"
        private const val PREF_SPOOF_ENABLED = "spoof_enabled"
    }

    private val prefs: XSharedPreferences by lazy {
        XSharedPreferences("com.android.imeisettings", PREFS_NAME).apply {
            makeWorldReadable()
        }
    }

    private val cipheringPatterns = listOf(
        "CIPHERING\\s*[:=]\\s*OFF".toRegex(),
        "CIPHERING\\s*[:=]\\s*0".toRegex(),
        "A5/0".toRegex(),
        "NO\\s*CIPHER".toRegex()
    )

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Self-check for module activity
        if (lpparam.packageName == "com.android.imeisettings") {
            hookSelfCheck(lpparam)
        }

        // Hook ALL packages for IMEI/DeviceId spoofing at TelephonyManager level
        hookImeiSpoofing(lpparam)

        // System phone process — security monitoring hooks
        if (lpparam.packageName == "com.android.phone" || lpparam.packageName == "android" || lpparam.packageName == "com.google.android.phone") {
            hookRilCiphering(lpparam)
            hookInboundSmsHandler(lpparam)
            hookWapPushHandler(lpparam)
            hookServiceStateTracker(lpparam)
            hookSilentCallDetection(lpparam)
            hookIdentityRequestProtection(lpparam)
        }
    }

    // ==================== IMEI SPOOFING (all apps) ====================

    private fun hookImeiSpoofing(lpparam: LoadPackageParam) {
        try {
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)

            // getDeviceId() — returns IMEI on GSM, MEID on CDMA
            XposedBridge.hookAllMethods(tmClass, "getDeviceId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val spoofed = getSpoofedImei(param, slotIndex = getSlotFromArgs(param))
                    if (spoofed != null) {
                        param.result = spoofed
                    }
                }
            })

            // getImei() — API 26+, returns IMEI specifically
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                XposedBridge.hookAllMethods(tmClass, "getImei", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val spoofed = getSpoofedImei(param, slotIndex = getSlotFromArgs(param))
                        if (spoofed != null) {
                            param.result = spoofed
                        }
                    }
                })
            }

            // getMeid() — API 26+, returns MEID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                XposedBridge.hookAllMethods(tmClass, "getMeid", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val spoofed = getSpoofedMeid(param)
                        if (spoofed != null) {
                            param.result = spoofed
                        }
                    }
                })
            }

            // getSubscriberId() — returns IMSI (hooked for logging, not spoofing by default)
            XposedBridge.hookAllMethods(tmClass, "getSubscriberId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (lpparam.packageName != "com.android.imeisettings") {
                        XposedBridge.log("$TAG: getSubscriberId() called by ${lpparam.packageName}")
                    }
                }
            })

            XposedBridge.log("$TAG: IMEI spoofing hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            // TelephonyManager not found in this classloader — expected for some system processes
        }
    }

    private fun getSlotFromArgs(param: XC_MethodHook.MethodHookParam): Int {
        return try {
            if (param.args.isNotEmpty() && param.args[0] is Int) param.args[0] as Int else 0
        } catch (_: Throwable) { 0 }
    }

    private fun getSpoofedImei(param: XC_MethodHook.MethodHookParam, slotIndex: Int): String? {
        return try {
            if (prefs.hasFileChanged()) prefs.reload()
            if (!prefs.getBoolean(PREF_SPOOF_ENABLED, false)) return null
            val key = if (slotIndex <= 0) PREF_SPOOFED_IMEI_1 else PREF_SPOOFED_IMEI_2
            val spoofed = prefs.getString(key, null)
            if (spoofed.isNullOrBlank()) return null
            XposedBridge.log("$TAG: Spoofing IMEI (slot $slotIndex) -> $spoofed")
            spoofed
        } catch (_: Throwable) { null }
    }

    private fun getSpoofedMeid(param: XC_MethodHook.MethodHookParam): String? {
        return try {
            if (prefs.hasFileChanged()) prefs.reload()
            if (!prefs.getBoolean(PREF_SPOOF_ENABLED, false)) return null
            val spoofed = prefs.getString(PREF_SPOOFED_MEID, null)
            if (spoofed.isNullOrBlank()) return null
            XposedBridge.log("$TAG: Spoofing MEID -> $spoofed")
            spoofed
        } catch (_: Throwable) { null }
    }

    private fun getContextFromTelephonyManager(param: XC_MethodHook.MethodHookParam): Context? {
        return try {
            XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
        } catch (_: Throwable) {
            try {
                XposedHelpers.callMethod(param.thisObject, "getContext") as? Context
            } catch (_: Throwable) { null }
        }
    }

    // ==================== END IMEI SPOOFING ====================

    private fun hookIdentityRequestProtection(lpparam: LoadPackageParam) {
        // PhoneSubInfoController: renamed/removed on Android 15+ (API 35)
        val subInfoClasses = if (Build.VERSION.SDK_INT >= 35) {
            arrayOf(
                "com.android.internal.telephony.subscription.PhoneSubInfoController",
                "com.android.internal.telephony.PhoneSubInfoController"
            )
        } else {
            arrayOf("com.android.internal.telephony.PhoneSubInfoController")
        }

        var hooked = false
        for (className in subInfoClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(clazz, "getSubscriberId", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callingUid = android.os.Binder.getCallingUid()
                        val callingPkg = try {
                            val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as android.content.Context
                            context.packageManager.getNameForUid(callingUid)
                        } catch (e: Exception) { "Unknown" }

                        if (callingUid == 1001 || callingUid == 1000) {
                            XposedBridge.log("$TAG: IMSI requested by system ($callingPkg). Checking context...")
                            triggerAlert(param.thisObject, "IDENTITY_REQUEST", "Network is requesting your IMSI (Identity Request). Possible tracking attempt!", severityLevel = 8)
                        }
                    }
                })
                hooked = true
                XposedBridge.log("$TAG: Identity hook: $className OK")
                break
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: $className not found, trying next...")
            }
        }
        if (!hooked) {
            XposedBridge.log("$TAG: PhoneSubInfoController not available on this Android version")
        }

        // RIL.getIMSI: check method exists before hooking
        try {
            val rilClass = XposedHelpers.findClass("com.android.internal.telephony.RIL", lpparam.classLoader)
            val hasGetIMSI = rilClass.declaredMethods.any { it.name == "getIMSI" }
            if (hasGetIMSI) {
                XposedBridge.hookAllMethods(rilClass, "getIMSI", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: RIL command 'getIMSI' detected!")
                        triggerAlert(param.thisObject, "IDENTITY_REQUEST_RIL", "Low-level IMSI fetch initiated by modem!", severityLevel = 9)
                    }
                })
            } else {
                XposedBridge.log("$TAG: RIL.getIMSI not found — HIDL-based RIL, skipping")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Identity Protection RIL hook error: ${e.message}")
        }
    }

    private fun hookSilentCallDetection(lpparam: LoadPackageParam) {
        // 1. Мониторинг входящих звонков на уровне RIL
        // processUnsolicited exists on RILJ (Java RIL) but may not exist on HIDL/AIDL RIL
        try {
            val rilClass = XposedHelpers.findClass("com.android.internal.telephony.RIL", lpparam.classLoader)
            val hasProcessUnsolicited = rilClass.declaredMethods.any { it.name == "processUnsolicited" }
            if (hasProcessUnsolicited) {
                XposedBridge.hookAllMethods(rilClass, "processUnsolicited", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val responseArg = param.args[0] ?: return
                        val respStr = responseArg.toString().uppercase()
                        if (respStr.contains("UNSOL_RESPONSE_CALL_STATE_CHANGED") || respStr.contains("1001")) {
                            checkCallStealthMode(param.thisObject)
                        }
                    }
                })
            } else {
                XposedBridge.log("$TAG: processUnsolicited not found in RIL (HIDL/AIDL-based). Silent call RIL hook skipped.")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: RIL silent call hook error: ${e.message}")
        }

        // 2. Мониторинг CallTracker — try multiple class names for different Android versions
        val callTrackerClasses = if (Build.VERSION.SDK_INT >= 35) {
            arrayOf(
                "com.android.internal.telephony.imsphone.ImsPhoneCallTracker",
                "com.android.internal.telephony.GsmCdmaCallTracker",
                "com.android.internal.telephony.CallTracker"
            )
        } else {
            arrayOf("com.android.internal.telephony.CallTracker")
        }

        for (className in callTrackerClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(clazz, "handleMessage", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val msg = param.args[0] as android.os.Message
                        if (msg.what == 2) {
                            checkCallStealthMode(param.thisObject)
                        }
                    }
                })
                XposedBridge.log("$TAG: CallTracker hook: $className OK")
                break
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: $className not found, trying next...")
            }
        }
    }

    private fun checkCallStealthMode(hookObject: Any) {
        try {
            val phone = try {
                XposedHelpers.getObjectField(hookObject, "mPhone")
            } catch (e: Exception) {
                XposedHelpers.callMethod(hookObject, "getPhone")
            }
            
            val ringingCall = XposedHelpers.callMethod(phone, "getRingingCall")
            val isRinging = XposedHelpers.callMethod(ringingCall, "isRinging") as Boolean

            if (isRinging) {
                val context = XposedHelpers.getObjectField(phone, "mContext") as android.content.Context
                
                // Проверяем активность экрана звонка через TelecomManager
                val telecomManager = context.getSystemService(android.content.Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                
                XposedHandler.postDelayed({
                    try {
                        val stillRinging = XposedHelpers.callMethod(ringingCall, "isRinging") as Boolean
                        val isInCall = telecomManager.isInCall
                        
                        // Если звонок еще идет в системе, но стандартный UI звонка не активен
                        if (stillRinging && !isInCall) {
                            terminateSilentCall(ringingCall)
                        }
                    } catch (e: Exception) {}
                }, 2000) // Даем 2 секунды системе на запуск интерфейса
            }
        } catch (e: Exception) {}
    }

    private fun terminateSilentCall(ringingCall: Any) {
        try {
            XposedHelpers.callMethod(ringingCall, "hangup")
            XposedBridge.log("$TAG: CRITICAL: Silent Call Blocked!")
            
            triggerAlert(ringingCall, "SILENT_CALL", "Detected and blocked a stealthy incoming call (Silent Call/Ping).")
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Failed to hangup silent call: ${e.message}")
        }
    }

    private object XposedHandler : android.os.Handler(android.os.Looper.getMainLooper())

    private fun hookSelfCheck(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.imeisettings.util.XposedCheck",
                lpparam.classLoader,
                "isModuleActive",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Self-check hook failed: ${e.message}")
        }
    }

    private fun hookRilCiphering(lpparam: LoadPackageParam) {
        try {
            val rilClass = XposedHelpers.findClass("com.android.internal.telephony.RIL", lpparam.classLoader)
            val hasProcessUnsolicited = rilClass.declaredMethods.any { it.name == "processUnsolicited" }
            if (!hasProcessUnsolicited) {
                XposedBridge.log("$TAG: processUnsolicited not in RIL (HIDL/AIDL). Ciphering hook skipped.")
                return
            }
            XposedBridge.hookAllMethods(rilClass, "processUnsolicited", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val responseArg = param.args[0] ?: return
                    if (responseArg is Parcel) parseRilParcel(param.thisObject, responseArg)
                    val respStr = responseArg.toString().uppercase()
                    if (cipheringPatterns.any { it.containsMatchIn(respStr) }) {
                        triggerAlert(param.thisObject, "CIPHERING_OFF", "CRITICAL: Encryption disabled (A5/0) detected!")
                    }
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error hooking RIL: ${e.message}")
        }
    }

    private fun parseRilParcel(rilObject: Any, p: Parcel) {
        val pos = p.dataPosition()
        try {
            val responseId = p.readInt()
            if (responseId == RIL_UNSOL_CIPHERING_INFO) {
                val status = p.readInt()
                if (status == 0) triggerAlert(rilObject, "CIPHERING_OFF", "CRITICAL: Encryption disabled (A5/0) detected via RIL Parcel!")
            }
        } catch (e: Exception) {
        } finally { p.setDataPosition(pos) }
    }

    private fun hookInboundSmsHandler(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.internal.telephony.InboundSmsHandler",
                lpparam.classLoader, "dispatchSmsPdus",
                Array<ByteArray>::class.java, String::class.java, Int::class.java,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pdus = (param.args[0] as? Array<ByteArray>) ?: return
                        val format = param.args[1] as String

                        // Helper to extract PID/DCS/UDL/UDHI from raw TP-DU when SmsMessage misses data
                        data class PduFields(val pid: Int, val dcs: Int, val udl: Int, val udhi: Boolean)

                        fun extractPduFields(pdu: ByteArray): PduFields {
                            try {
                                var idx = 0
                                // 1. Skip SCA (Service Center Address)
                                val scaLen = pdu[0].toInt() and 0xFF
                                idx += 1 + scaLen
                                if (idx >= pdu.size) return PduFields(0, 0, 0, false)

                                // 2. First Octet
                                val firstOctet = pdu[idx].toInt() and 0xFF
                                val mti = firstOctet and 0x03
                                if (mti != 0) return PduFields(0, 0, 0, false) // Only handle SMS-DELIVER (MTI=0)

                                val udhi = (firstOctet and 0x40) != 0
                                idx += 1
                                if (idx >= pdu.size) return PduFields(0, 0, 0, udhi)

                                // 3. Skip OA (Originating Address)
                                val oaLenSemiOctets = pdu[idx].toInt() and 0xFF
                                val oaLenOctets = (oaLenSemiOctets + 1) / 2
                                idx += 1 + 1 + oaLenOctets // +1 for length, +1 for TOA, +addr
                                if (idx >= pdu.size) return PduFields(0, 0, 0, udhi)

                                // 4. PID (Protocol Identifier)
                                val pid = pdu[idx].toInt() and 0xFF
                                idx += 1
                                if (idx >= pdu.size) return PduFields(pid, 0, 0, udhi)

                                // 5. DCS (Data Coding Scheme)
                                val dcs = pdu[idx].toInt() and 0xFF
                                idx += 1
                                if (idx >= pdu.size) return PduFields(pid, dcs, 0, udhi)

                                // 6. Skip SCTS (Service Centre Time Stamp) - 7 octets
                                idx += 7
                                if (idx >= pdu.size) return PduFields(pid, dcs, 0, udhi)

                                // 7. UDL (User Data Length)
                                val udl = pdu[idx].toInt() and 0xFF
                                return PduFields(pid, dcs, udl, udhi)
                            } catch (e: Exception) {
                                return PduFields(0, 0, 0, false)
                            }
                        }

                        for (pdu in pdus) {
                            val pduHex = pdu.joinToString("") { "%02x".format(it) }

                            // First try platform parsing
                            val sms = try { SmsMessage.createFromPdu(pdu, format) } catch (_: Throwable) { null }

                            val pid = sms?.protocolIdentifier ?: 0
                            var dcs = 0
                            val originAddr = sms?.originatingAddress ?: "Unknown"
                            val smsc = sms?.serviceCenterAddress ?: "Unknown"

                            try {
                                if (sms != null) {
                                    val wrapped = XposedHelpers.getObjectField(sms, "mWrappedSmsMessage")
                                    dcs = try { XposedHelpers.callMethod(wrapped, "getDataCodingScheme") as Int } catch (_: Throwable) { 0 }
                                }
                            } catch (_: Throwable) { /* ignore */ }

                            val udlFromSms = try { 
                                sms?.let { 
                                    // try reflection for user data length if available
                                    val wrapped = XposedHelpers.getObjectField(it, "mWrappedSmsMessage")
                                    XposedHelpers.callMethod(wrapped, "getUserDataLength") as? Int ?: 0
                                } ?: 0 
                            } catch (_: Throwable) { 0 }

                            // If core fields look missing or body empty, use raw PDU extractor
                            if ((sms == null) || (sms.messageBody.isNullOrEmpty() && (dcs == 0 || pid == 0 || udlFromSms == 0))) {
                                val fallback = extractPduFields(pdu)
                                val finalPid = if (pid == 0) fallback.pid else pid
                                val finalDcs = if (dcs == 0) fallback.dcs else dcs
                                
                                if (sms == null) XposedBridge.log("$TAG: Fallback PDU parse used for $pduHex -> pid=${fallback.pid} dcs=${fallback.dcs} udl=${fallback.udl} udhi=${fallback.udhi}")

                                // 1. Flash SMS Detection (Class 0)
                                val isFlash = (finalDcs and 0x10 != 0 && finalDcs and 0x0F == 0)

                                // 2. Детекция Silent SMS (Type 0 или DCS Hidden/Discard)
                                val isSilent = (finalPid == 0x40) || (finalDcs in 0xC0..0xCF) || (finalDcs and 0x10 != 0 && finalDcs and 0x20 == 0)

                                // 3. Детекция Binary/OTA атак (SIM Toolkit / SIMJacker)
                                val isBinary = (finalPid in 0x41..0x47) || (finalPid == 0x7F) || (finalDcs and 0x04 != 0) || (finalPid == 0x20)

                                // 4. Детекция атаки через сверхсильный сигнал (RILDefender logic)
                                var isFbsAttack = false
                                try {
                                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as android.content.Context
                                    val tm = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                                    
                                    val dbm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                        tm.signalStrength?.let { 
                                            try { XposedHelpers.callMethod(it, "getDbm") as Int } catch(_: Throwable) { -100 }
                                        } ?: -100
                                    } else {
                                        -100 // Fallback for old APIs if needed
                                    }
                                    
                                    if (dbm > RSSI_THRESHOLD_FBS) isFbsAttack = true
                                } catch (_: Throwable) {}

                                if (isSilent || isBinary || isFlash || isFbsAttack) {
                                    handleSmsAttack(param, pduHex, originAddr, smsc, isBinary, isFlash, isFbsAttack)
                                    return
                                }
                            } else {
                                // 1. Flash SMS Detection (Class 0)
                                val isFlash = sms.messageClass == SmsMessage.MessageClass.CLASS_0 || (dcs and 0x10 != 0 && dcs and 0x0F == 0)

                                // 2. Детекция Silent SMS (Type 0 или DCS Hidden/Discard)
                                val isSilent = (pid == 0x40) || (dcs in 0xC0..0xCF) || (dcs and 0x10 != 0 && dcs and 0x20 == 0)

                                // 3. Детекция Binary/OTA атак (SIM Toolkit / SIMJacker)
                                val isBinary = (pid in 0x41..0x47) || (pid == 0x7F) || (dcs and 0x04 != 0) || (pid == 0x20) ||
                                              (try { XposedHelpers.callMethod(sms, "isUsimDataDownload") as Boolean } catch(_: Throwable) { false })

                                // 4. Детекция атаки через сверхсильный сигнал (RILDefender logic)
                                var isFbsAttack = false
                                try {
                                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as android.content.Context
                                    val tm = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                                    
                                    val dbm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                        tm.signalStrength?.let { 
                                            try { XposedHelpers.callMethod(it, "getDbm") as Int } catch(_: Throwable) { -100 }
                                        } ?: -100
                                    } else {
                                        -100 // Fallback for old APIs if needed
                                    }

                                    if (dbm > RSSI_THRESHOLD_FBS) isFbsAttack = true
                                } catch (_: Throwable) {}

                                if (isSilent || isBinary || isFlash || isFbsAttack) {
                                    handleSmsAttack(param, pduHex, originAddr, smsc, isBinary, isFlash, isFbsAttack)
                                    return
                                }
                            }
                        }
                    }

                    private fun handleSmsAttack(param: MethodHookParam, pduHex: String, originAddr: String, smsc: String, isBinary: Boolean, isFlash: Boolean, isFbs: Boolean = false) {
                        val type = when {
                            isFbs -> "FBS_SMS_ATTACK"
                            isBinary -> "BINARY_ATTACK"
                            isFlash -> "FLASH_SMS"
                            else -> "SILENT_SMS"
                        }
                        val desc = when {
                            isFbs -> "SMS received while abnormally strong signal detected. Highly likely Fake Base Station attack!"
                            isBinary -> "Binary SIM/OTA attack intercepted (SIMJacker/WIB). Critical threat blocked."
                            isFlash -> "Flash (Class 0) SMS intercepted. Potential phishing/spam blocked."
                            else -> "Silent SMS (Type-0/Hidden) intercepted. Spoofing success ACK."
                        }

                        triggerAlert(param.thisObject, type, desc, pduHex, extra = mapOf("oa" to originAddr, "smsc" to smsc))

                        // HONEYPOT: Возвращаем "Успешно", но блокируем дальнейшую цепочку
                        param.result = 1 // Activity.RESULT_OK
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error hooking InboundSmsHandler: ${e.message}")
        }
    }

    private fun hookWapPushHandler(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.internal.telephony.WapPushOverSms",
                lpparam.classLoader, "dispatchWapPdu",
                ByteArray::class.java, String::class.java, "com.android.internal.telephony.InboundSmsHandler",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pdu = param.args[0] as ByteArray
                        val pduHex = pdu.joinToString("") { "%02x".format(it) }
                        
                        // Простая детекция опасных WAP-маркеров (например, автоматическая загрузка)
                        if (pduHex.contains("4c6f636174696f6e") || pdu.size < 10) { // "Location" или подозрительно короткий
                            triggerAlert(param.thisObject, "WAP_PUSH_THREAT", "Suspicious WAP Push intercepted.", pduHex)
                            param.result = 1 // Spoof success
                        }
                    }
                }
            )
        } catch (e: Throwable) { /* Ожидаемо на некоторых версиях Android */ }
    }

    private fun hookServiceStateTracker(lpparam: LoadPackageParam) {
        try {
            val sstClass = XposedHelpers.findClass("com.android.internal.telephony.ServiceStateTracker", lpparam.classLoader)
            
            // Hook cell info updates for FBS detection
            XposedBridge.hookAllMethods(sstClass, "pollStateDone", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sst = param.thisObject
                    val serviceState = XposedHelpers.getObjectField(sst, "mSS") as? android.telephony.ServiceState ?: return
                    
                    // 1. Detect suspicious signal strength (RSSI)
                    try {
                        val signalStrength = XposedHelpers.getObjectField(sst, "mSignalStrength") as? android.telephony.SignalStrength
                        val dbm = signalStrength?.let { 
                            try { XposedHelpers.callMethod(it, "getDbm") as Int } catch(_: Throwable) { -100 }
                        } ?: -100

                        if (dbm > RSSI_THRESHOLD_FBS) {
                            triggerAlert(sst, "FBS_SUSPICION", "Abnormally strong signal detected ($dbm dBm). Potential Fake Base Station nearby!", extra = mapOf("rssi" to dbm.toString()))
                        }
                    } catch (_: Throwable) {}

                    // 2. Basic Cell Identity Validation (MCC/MNC)
                    try {
                        val operatorNumeric = try { XposedHelpers.callMethod(serviceState, "getOperatorNumeric") as? String ?: "" } catch(_: Throwable) { "" }
                        if (operatorNumeric.length in 5..6) {
                            val mcc = operatorNumeric.substring(0, 3).toInt()
                            if (mcc == 0 || mcc >= 999) {
                                triggerAlert(sst, "FBS_SUSPICION", "Invalid MCC detected ($mcc). Potential Fake Base Station!", extra = mapOf("operator" to operatorNumeric))
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error hooking ServiceStateTracker: ${e.message}")
        }
    }

    private fun triggerAlert(hookObject: Any, type: String, description: String, pduHex: String? = null, severityLevel: Int? = null, extra: Map<String, String>? = null) {
        try {
            val context = XposedHelpers.getObjectField(hookObject, "mContext") as Context
            val intent = Intent(ACTION_EVENT).apply {
                setPackage("com.android.imeisettings")
                putExtra("eventType", type)
                putExtra("description", description)
                putExtra("pdu", pduHex)
                putExtra("severity", severityLevel ?: when(type) {
                    "BINARY_ATTACK" -> 10
                    "FBS_SUSPICION" -> 9
                    "IDENTITY_REQUEST_RIL" -> 9
                    "IDENTITY_REQUEST" -> 8
                    "SILENT_CALL" -> 8
                    "FLASH_SMS" -> 6
                    else -> 7
                })
                extra?.forEach { (k, v) -> putExtra(k, v) }
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            try {
                val userAll = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL") as UserHandle
                XposedHelpers.callMethod(context, "sendBroadcastAsUser", intent, userAll)
            } catch (e: Throwable) { context.sendBroadcast(intent) }
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Failed to trigger alert: ${e.message}")
        }
    }
}
