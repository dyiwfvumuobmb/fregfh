package com.android.imeisettings.util

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Specialized IMEI modification methods for all major SoC vendors and OEMs.
 */
object OemSpecificUtil {
    private const val TAG = "CONSUL_OEM_UTIL"

    // ======================== XIAOMI ========================

    fun tryXiaomiQualcomm(context: Context): Boolean {
        Log.d(TAG, "Attempting Xiaomi Qualcomm Diag activation...")
        return try {
            RootUtil.executeRootCommand("setprop sys.usb.config diag,adb")
            RootUtil.executeRootCommand("setprop persist.sys.usb.config diag,adb")
            val res1 = OemRilUtil.sendAtCommand(context, "AT+QCN=1", 0)
            res1.contains("OK")
        } catch (e: Exception) {
            false
        }
    }

    fun tryXiaomiMtkDirect(imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Xiaomi MTK Direct NVRAM Write...")
        if (!RootUtil.isValidImei(imei1) || !RootUtil.isValidImei(imei2)) return false
        return try {
            val nvramPaths = arrayOf(
                "/data/nvram/md/NVRAM/NVD_IMEI/MP0B_001",
                "/vendor/nvdata/md/NVRAM/NVD_IMEI/MP0B_001",
                "/mnt/vendor/nvdata/md/NVRAM/NVD_IMEI/MP0B_001"
            )
            for (path in nvramPaths) {
                if (!RootUtil.fileExists(path)) continue
                Log.d(TAG, "Xiaomi MTK NVRAM path found: $path")
                RootUtil.backupFile(path)

                val imei1Bytes = encodeImeiForNvram(imei1)
                val imei2Bytes = encodeImeiForNvram(imei2)
                if (imei1Bytes == null || imei2Bytes == null) continue

                // NVRAM IMEI format: 4-byte header + 8-byte BCD-encoded IMEI per slot
                val hexCmd1 = imei1Bytes.joinToString("") { "\\x%02x".format(it) }
                val hexCmd2 = imei2Bytes.joinToString("") { "\\x%02x".format(it) }

                // Write IMEI1 at offset 0x04, IMEI2 at offset 0x0C
                val writeOk = RootUtil.executeRootCommand(
                    "printf '$hexCmd1' | dd of=$path bs=1 seek=4 conv=notrunc 2>/dev/null && " +
                    "printf '$hexCmd2' | dd of=$path bs=1 seek=12 conv=notrunc 2>/dev/null"
                )
                if (writeOk) {
                    Log.i(TAG, "Xiaomi MTK NVRAM write OK: $path")
                    RootUtil.executeRootCommand("setprop sys.radio.restart 1")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Xiaomi MTK direct failed: ${e.message}")
            false
        }
    }

    private fun encodeImeiForNvram(imei: String): ByteArray? {
        if (imei.length != 15) return null
        val bytes = ByteArray(8)
        bytes[0] = ((imei[0].digitToInt() shl 4) or 0x0A).toByte()
        for (i in 1..6) {
            val high = imei[i * 2].digitToInt()
            val low = imei[i * 2 - 1].digitToInt()
            bytes[i] = ((high shl 4) or low).toByte()
        }
        bytes[7] = (0xF0 or imei[13].digitToInt()).toByte()
        return bytes
    }

    // ======================== SAMSUNG ========================

    fun trySamsungMsl(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Samsung MSL Bypass...")
        return try {
            val intent = Intent("com.sec.android.app.servicemodeapp.AS_COMMAND")
            intent.setPackage("com.sec.android.app.servicemodeapp")
            intent.putExtra("command", "AT+IMEI=1,7,\"$imei1\"")
            context.sendBroadcast(intent)

            val res = OemRilUtil.sendAtCommand(context, "AT+IMEI=1,7,\"$imei1\"", 0)
            if (res.contains("OK")) {
                OemRilUtil.sendAtCommand(context, "AT+IMEI=1,10,\"$imei2\"", 1)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Samsung Exynos: Uses Samsung-specific RIL interface and EFS partition.
     * Supports Galaxy S-series, A-series, M-series with Exynos SoC.
     */
    fun trySamsungExynos(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Samsung Exynos IMEI write...")
        return try {
            // 1. Samsung-specific AT commands via RIL
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 2. Try Samsung OEM hook via ServiceManager
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val phoneBinder = getService.invoke(null, "phone") as? IBinder

            if (phoneBinder != null) {
                val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val iTelephony = asInterface.invoke(null, phoneBinder)
                if (iTelephony != null) {
                    val methods = iTelephony.javaClass.methods
                    for (m in methods) {
                        if (m.name.contains("invokeOemRilRequest") || m.name.contains("sendOemRilRequest")) {
                            try {
                                m.isAccessible = true
                                val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                m.invoke(iTelephony, cmd)
                                Log.d(TAG, "Samsung OEM RIL invoked via ${m.name}")
                                return true
                            } catch (e: Exception) {
                                Log.w(TAG, "Samsung OEM method ${m.name} failed: ${e.message}")
                            }
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Samsung Exynos failed: ${e.message}")
            false
        }
    }

    // ======================== GOOGLE TENSOR ========================

    /**
     * Google Tensor (Pixel 6/7/8/9): Uses Qualcomm-derived RIL with Google modifications.
     * Tensor chips are Samsung-manufactured but use Google's custom RIL stack.
     */
    fun tryGoogleTensor(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Google Tensor IMEI write...")
        return try {
            // 1. Standard AT+EGMR via Google's RIL implementation
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 2. Try system properties (Tensor uses persist.vendor.radio)
            SystemPropertiesProxy.set(context, "persist.vendor.radio.imei1", imei1)
            SystemPropertiesProxy.set(context, "persist.vendor.radio.imei2", imei2)

            // 3. Try Google-specific RIL OEM hook
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "phone") as? IBinder
            if (binder != null) {
                val phoneStub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                val proxy = phoneStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                if (proxy != null) {
                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("invokeOemRilRequestRaw")) {
                            try {
                                m.isAccessible = true
                                val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                m.invoke(proxy, cmd)
                                return true
                            } catch (e: Exception) { /* continue */ }
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Google Tensor failed: ${e.message}")
            false
        }
    }

    // ======================== HISILICON KIRIN ========================

    /**
     * HiSilicon Kirin (Huawei/Honor): Uses Huawei's proprietary RIL and NVRAM interface.
     * Supports Kirin 710/810/820/980/990/9000.
     */
    fun tryHuaweiKirin(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Huawei Kirin IMEI write...")
        return try {
            // 1. Huawei-specific AT commands
            val res1 = OemRilUtil.sendAtCommand(context, "AT^PHYNUM=IMEI,$imei1", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT^PHYNUM=IMEI,$imei2", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 2. Try standard EGMR as fallback
            val res3 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res4 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res3.contains("OK") || res4.contains("OK")) return true

            // 3. Try Huawei's HwTelephonyManager
            try {
                val hwTmClass = Class.forName("com.huawei.telephony.HwTelephonyManager")
                val getInstance = hwTmClass.getMethod("getDefault")
                val hwTm = getInstance.invoke(null)
                if (hwTm != null) {
                    for (m in hwTmClass.methods) {
                        if (m.name.contains("sendOemRilRequest") || m.name.contains("invokeOemRilRequest")) {
                            try {
                                m.isAccessible = true
                                val cmd = "AT^PHYNUM=IMEI,$imei1\r\u0000".toByteArray()
                                when (m.parameterCount) {
                                    1 -> m.invoke(hwTm, cmd)
                                    2 -> m.invoke(hwTm, cmd, 0)
                                }
                                return true
                            } catch (e: Exception) { /* continue */ }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HwTelephonyManager not available: ${e.message}")
            }

            // 4. Root fallback: direct NV write via Huawei's tool
            if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
                RootUtil.executeRootCommand("printf 'AT^PHYNUM=IMEI,$imei1\r' > /dev/appvcom1")
                RootUtil.executeRootCommand("printf 'AT^PHYNUM=IMEI,$imei2\r' > /dev/appvcom2")
            } else {
                Log.e(TAG, "Huawei root fallback: IMEI validation failed")
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Huawei Kirin failed: ${e.message}")
            false
        }
    }

    // ======================== UNISOC TIGER ========================

    /**
     * Unisoc Tiger T-series (T606/T610/T612/T616/T700/T710/T760/T770/T820/T900).
     * Modern Unisoc chips use enhanced AT interface with SPIMEI and SPSN commands.
     */
    fun tryUnisocTiger(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Unisoc Tiger-series IMEI write...")
        return try {
            // 1. SPIMEI command (primary for Unisoc Tiger)
            val res1 = OemRilUtil.sendAtCommand(context, "AT+SPIMEI=1,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+SPIMEI=2,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 2. Try SPSN for newer T-series (T820+)
            val res3 = OemRilUtil.sendAtCommand(context, "AT+SPSN=1,6,2,\"$imei1\"", 0)
            val res4 = OemRilUtil.sendAtCommand(context, "AT+SPSN=1,6,2,\"$imei2\"", 1)
            if (res3.contains("OK") || res4.contains("OK")) return true

            // 3. Standard EGMR fallback
            val res5 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res6 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res5.contains("OK") || res6.contains("OK")) return true

            // 4. Root: direct NV via Unisoc's calibration interface
            if (!RootUtil.isValidImei(imei1) || !RootUtil.isValidImei(imei2)) {
                Log.e(TAG, "Unisoc root fallback: IMEI validation failed")
                return false
            }
            val nvSuccess = RootUtil.executeRootCommand(
                "printf '%s' '$imei1' > /productinfo/imei1.txt && " +
                "printf '%s' '$imei2' > /productinfo/imei2.txt"
            )
            nvSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Unisoc Tiger failed: ${e.message}")
            false
        }
    }

    // ======================== QUALCOMM SNAPDRAGON 8 GEN / 8 ELITE ========================

    /**
     * Qualcomm Snapdragon 8 Gen 1/2/3/4 + 8 Elite / 8s Elite (SM8450/SM8550/SM8650/SM8750/SM8735).
     * Modern Snapdragon uses AIDL-based HAL with vendor extensions.
     * 8 Elite (2025) and 8s Elite (2025) use enhanced AIDL v2 with IRadioModem.
     */
    fun tryQualcommModern(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Modern Qualcomm (8 Gen / 8 Elite series) IMEI write...")
        return try {
            // 1. Try Qualcomm AIDL vendor radio interface (including 8 Elite AIDL v2)
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            val vendorServices = arrayOf(
                "vendor.qti.hardware.radio.qtiradio.IQtiRadioStable/slot1",
                "vendor.qti.hardware.radio.qtiradio.IQtiRadio/slot1",
                "vendor.qti.hardware.radio.IRadioModem/slot1",
                "vendor.qti.hardware.radio.modem.IRadioModem/slot1",
                "qti.radio.slot1"
            )

            for (serviceName in vendorServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    Log.d(TAG, "Found Qualcomm vendor service: $serviceName")

                    val stubClassName = serviceName.substringBefore("/") + "\$Stub"
                    val stubClass = Class.forName(stubClassName)
                    val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                    val proxy = asInterface.invoke(null, binder) ?: continue

                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("sendOemRilRequest") || m.name.contains("invokeOemRilRequest")) {
                            try {
                                m.isAccessible = true
                                val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                m.invoke(proxy, cmd)
                                return true
                            } catch (e: Exception) { /* continue */ }
                        }
                    }
                } catch (e: Exception) { /* try next service */ }
            }

            // 2. Standard AT+EGMR
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 3. Qualcomm DIAG mode for persistent NV write
            RootUtil.executeRootCommand("setprop sys.usb.config diag,adb")
            val diagRes = OemRilUtil.sendAtCommand(context, "AT+QCN=1", 0)
            if (diagRes.contains("OK")) {
                val w1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
                val w2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
                RootUtil.executeRootCommand("setprop sys.usb.config adb")
                return w1.contains("OK") || w2.contains("OK")
            }

            // 4. Qualcomm /persist/radio/imei file write (from imei_patcher)
            // Includes paths for Snapdragon 8 Elite (SM8750) and 8s Elite (SM8735)
            if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
                val persistPaths = arrayOf(
                    "/persist/radio/imei",
                    "/persist/radio/imei1",
                    "/mnt/vendor/persist/radio/imei",
                    "/mnt/vendor/persist/radio/imei1",
                    "/mnt/vendor/persist/data/imei",
                    "/vendor/persist/radio/imei",
                    "/efs/imei/mps_code.dat"
                )
                for (path in persistPaths) {
                    val exists = RootUtil.executeWithOutput("ls $path 2>/dev/null").isNotEmpty()
                    if (exists) {
                        Log.d(TAG, "Qualcomm persist file found: $path")
                        val writeOk = RootUtil.executeRootCommand(
                            "echo '$imei1' > $path && chmod 644 $path"
                        )
                        if (writeOk) {
                            RootUtil.executeRootCommand("setprop sys.radio.restart 1")
                            return true
                        }
                    }
                }

                // 5. service call iphonesubinfo method
                val svcRes = RootUtil.executeWithOutput(
                    "service call iphonesubinfo 8 i32 1 s16 $imei1"
                )
                if (svcRes.isNotEmpty() && !svcRes.contains("error", ignoreCase = true)) {
                    Log.d(TAG, "Qualcomm service call method succeeded")
                    return true
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Modern Qualcomm failed: ${e.message}")
            false
        }
    }

    // ======================== MEDIATEK DIMENSITY ========================

    /**
     * MediaTek Dimensity 700/800/900/1000/1200/7000/8000/8400/9000/9200/9300/9400/9400+/9500 series.
     * Modern Dimensity uses AIDL RadioEx HAL, but some models need specific approaches.
     * Dimensity 9400+ (MT6991) and 8400 (MT6990) use enhanced AIDL v2 RadioEx.
     */
    fun tryMtkDimensity(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting MediaTek Dimensity IMEI write...")
        return try {
            // 1. Try modern AIDL RadioEx (handled by MtkRadioExUtil)
            if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) return true

            // 2. Try Dimensity-specific vendor service names (including 9400+/8400 new HAL names)
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            val dimensityServices = arrayOf(
                "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/slot1",
                "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/mtkSlot1",
                "vendor.mediatek.hardware.mtkradioex.modem.V2_0.IMtkRadioExModem/slot1",
                "vendor.mediatek.hardware.radio.modem.IRadioModem/slot1",
                "mtkRadioEx1"
            )

            for (serviceName in dimensityServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    val descriptor = serviceName.substringBefore("/")
                    val stubClass = Class.forName("$descriptor\$Stub")
                    val proxy = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: continue

                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("sendRequestStrings") || m.name.contains("sendAtCommand")) {
                            try {
                                m.isAccessible = true
                                val cmd = arrayOf("AT+EGMR=1,7,\"$imei1\"", "")
                                m.invoke(proxy, 7, cmd, 3)
                                return true
                            } catch (e: Exception) { /* continue */ }
                        }
                    }
                } catch (e: Exception) { /* try next */ }
            }

            // 3. Classic AT commands
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            res1.contains("OK") || res2.contains("OK")
        } catch (e: Exception) {
            Log.e(TAG, "MTK Dimensity failed: ${e.message}")
            false
        }
    }

    // ======================== REALME ========================

    /**
     * Realme devices (GT series, Number series, C/Narzo).
     * Realme uses either Qualcomm or MediaTek SoCs with OPPO/Realme-specific RIL extensions.
     * ColorOS/Realme UI has custom telephony providers.
     */
    fun tryRealmeQualcomm(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Realme Qualcomm IMEI write...")
        return try {
            // 1. OPPO/Realme vendor telephony service
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            val oppoServices = arrayOf(
                "oppo_telephony",
                "oplus_telephony",
                "vendor.oplus.hardware.radio"
            )

            for (serviceName in oppoServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    Log.d(TAG, "Found Realme/OPPO vendor service: $serviceName")

                    val proxy = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder) ?: continue

                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("invokeOemRilRequest") || m.name.contains("sendOemRilRequest")) {
                            try {
                                m.isAccessible = true
                                val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                m.invoke(proxy, cmd)
                                Log.d(TAG, "Realme OEM RIL invoked via ${m.name}")
                                return true
                            } catch (e: Exception) { /* continue */ }
                        }
                    }
                } catch (e: Exception) { /* try next service */ }
            }

            // 2. Realme Deep Testing mode AT commands
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 3. Qualcomm DIAG mode for Realme
            RootUtil.executeRootCommand("setprop sys.usb.config diag,adb")
            val diagRes = OemRilUtil.sendAtCommand(context, "AT+QCN=1", 0)
            if (diagRes.contains("OK")) {
                val w1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
                val w2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
                RootUtil.executeRootCommand("setprop sys.usb.config adb")
                return w1.contains("OK") || w2.contains("OK")
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Realme Qualcomm failed: ${e.message}")
            false
        }
    }

    fun tryRealmeMtk(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Realme MTK IMEI write...")
        return try {
            // 1. Try RadioEx first (Realme V-series, C-series with MTK)
            if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) return true

            // 2. OPPO EngineerMode AT injection
            val intent = Intent("com.oppo.engineermode.ACTION_AT_COMMAND")
            intent.setPackage("com.oppo.engineermode")
            intent.putExtra("at_command", "AT+EGMR=1,7,\"$imei1\"")
            context.sendBroadcast(intent)

            // 3. Standard AT via RIL
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            res1.contains("OK") || res2.contains("OK")
        } catch (e: Exception) {
            Log.e(TAG, "Realme MTK failed: ${e.message}")
            false
        }
    }

    // ======================== INFINIX (Transsion Holdings) ========================

    /**
     * Infinix devices with MediaTek Dimensity 8200/8300/9200/9300+ SoCs.
     * Infinix (Transsion Holdings) uses XOS skin with MTK vendor HAL.
     * Dimensity 8200+ uses AIDL RadioEx HAL with Transsion-specific service names.
     *
     * Supported models: NOTE 40 Pro+ 5G, GT 20 Pro, HOT 50 Pro+, ZERO 40 5G, NOTE 30 Pro, etc.
     */
    fun tryInfinixDimensity(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Infinix Dimensity 8200+ IMEI write...")
        return try {
            // 1. Try modern AIDL RadioEx (primary for Dimensity 8200+)
            if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) {
                Log.i(TAG, "Infinix: AIDL RadioEx succeeded")
                return true
            }

            // 2. Try Transsion-specific vendor service names
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            val transsionServices = arrayOf(
                "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/slot1",
                "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/mtkSlot1",
                "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/mtkEm1",
                "mtkRadioEx1",
                "mtkRadioExModem1"
            )

            for (serviceName in transsionServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    Log.d(TAG, "Infinix: Found service $serviceName")
                    val descriptor = serviceName.substringBefore("/")
                    val stubClass = Class.forName("$descriptor\$Stub")
                    val proxy = stubClass.getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder) ?: continue

                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("sendRequestStrings") || m.name.contains("sendAtCommand")) {
                            try {
                                m.isAccessible = true
                                val cmd1 = arrayOf("AT+EGMR=1,7,\"$imei1\"", "")
                                m.invoke(proxy, 7, cmd1, 3)
                                Log.d(TAG, "Infinix: SIM1 AT+EGMR sent via ${m.name}")

                                // SIM2
                                val binder2 = getService.invoke(null,
                                    serviceName.replace("slot1", "slot2")
                                        .replace("mtkEm1", "mtkEm2")
                                        .replace("mtkRadioEx1", "mtkRadioEx2")
                                        .replace("mtkRadioExModem1", "mtkRadioExModem2")
                                ) as? IBinder
                                if (binder2 != null) {
                                    val proxy2 = stubClass.getMethod("asInterface", IBinder::class.java)
                                        .invoke(null, binder2)
                                    if (proxy2 != null) {
                                        val m2 = proxy2.javaClass.getMethod(m.name, *m.parameterTypes)
                                        m2.isAccessible = true
                                        val cmd2 = arrayOf("AT+EGMR=1,10,\"$imei2\"", "")
                                        m2.invoke(proxy2, 10, cmd2, 3)
                                    }
                                }
                                return true
                            } catch (e: Exception) {
                                Log.w(TAG, "Infinix service method ${m.name} failed: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) { /* try next service */ }
            }

            // 3. Try Transsion's PhoneManager (XOS-specific telephony extension)
            try {
                val transsionClasses = arrayOf(
                    "com.transsion.telephony.TranssionTelephonyManager",
                    "com.transsion.telephony.TelephonyManagerEx"
                )
                for (className in transsionClasses) {
                    try {
                        val clazz = Class.forName(className)
                        val instance = clazz.getMethod("getDefault").invoke(null) ?: continue
                        for (m in clazz.methods) {
                            if (m.name.contains("sendAtCommand") || m.name.contains("invokeOemRilRequest")) {
                                try {
                                    m.isAccessible = true
                                    when (m.parameterCount) {
                                        2 -> m.invoke(instance, "AT+EGMR=1,7,\"$imei1\"\r", 0)
                                        3 -> m.invoke(instance, 0, "AT+EGMR=1,7,\"$imei1\"\r", null)
                                    }
                                    Log.d(TAG, "Infinix: Transsion TelephonyManager succeeded via ${m.name}")
                                    return true
                                } catch (e: Exception) { /* continue */ }
                            }
                        }
                    } catch (e: Exception) { /* class not found, try next */ }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Infinix Transsion manager not available: ${e.message}")
            }

            // 4. Try Infinix EngineerMode broadcast (XOS engineer mode)
            try {
                val engineerIntents = arrayOf(
                    "com.transsion.engineermode.ACTION_AT_COMMAND",
                    "com.mediatek.engineermode.ACTION_AT_COMMAND"
                )
                for (action in engineerIntents) {
                    val intent = Intent(action)
                    intent.putExtra("at_command", "AT+EGMR=1,7,\"$imei1\"")
                    intent.putExtra("slot_id", 0)
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Infinix EngineerMode broadcast failed: ${e.message}")
            }

            // 5. Classic AT commands via OemRilUtil (MTK backend)
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 6. Root fallback: NVRAM direct write for Dimensity 8200+ partition layout
            if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
                // Dimensity 8200+ uses /vendor/nvdata or /mnt/vendor/nvdata
                val nvramPaths = arrayOf(
                    "/mnt/vendor/nvdata/APCFG/APRDEB/BARCODE",
                    "/vendor/nvdata/APCFG/APRDEB/BARCODE",
                    "/data/nvram/APCFG/APRDEB/BARCODE"
                )
                for (nvPath in nvramPaths) {
                    val exists = RootUtil.executeWithOutput("ls $nvPath").isNotEmpty()
                    if (exists) {
                        Log.d(TAG, "Infinix: Found NVRAM at $nvPath")
                        // Backup first
                        RootUtil.executeRootCommand("cp $nvPath ${nvPath}.bak")
                        // Write IMEI to NVRAM barcode area
                        if (RootUtil.executeRootCommand("printf '%s' '$imei1' > $nvPath")) {
                            Log.i(TAG, "Infinix: NVRAM IMEI written to $nvPath")
                            return true
                        }
                    }
                }
            } else {
                Log.e(TAG, "Infinix root fallback: IMEI validation failed")
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Infinix Dimensity failed: ${e.message}")
            false
        }
    }

    /**
     * Infinix devices with MediaTek Helio G-series (G85/G88/G96/G99/G100).
     * Older Infinix models use standard MTK AT+EGMR path.
     */
    fun tryInfinixHelio(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Infinix Helio IMEI write...")
        return try {
            // 1. Standard RadioEx (HIDL for older chips)
            if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) return true

            // 2. Classic AT commands
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 3. MTK EngineerMode broadcast
            val intent = Intent("com.mediatek.engineermode.ACTION_AT_COMMAND")
            intent.putExtra("at_command", "AT+EGMR=1,7,\"$imei1\"")
            context.sendBroadcast(intent)

            // 4. NVRAM fallback
            if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
                val nvPath = "/data/nvram/APCFG/APRDEB/BARCODE"
                if (RootUtil.executeWithOutput("ls $nvPath").isNotEmpty()) {
                    RootUtil.executeRootCommand("cp $nvPath ${nvPath}.bak")
                    if (RootUtil.executeRootCommand("printf '%s' '$imei1' > $nvPath")) {
                        return true
                    }
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Infinix Helio failed: ${e.message}")
            false
        }
    }

    // ======================== OPPO ========================

    fun tryOppo(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting OPPO IMEI write...")
        return try {
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true
            
            // OPPO-specific engineer mode
            val intent = Intent("com.oppo.engineermode.ACTION_AT_COMMAND")
            intent.setPackage("com.oplus.engineermode")
            intent.putExtra("at_command", "AT+EGMR=1,7,\"$imei1\"")
            context.sendBroadcast(intent)
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "OPPO failed: ${e.message}")
            false
        }
    }

    // ======================== QUALCOMM DIAG NV550 ========================

    /**
     * Direct NV Item 550 write via Qualcomm DIAG interface (/dev/diag).
     * NV_UE_IMEI_I = Item 550, NV_UE_IMEI_II = Item 551 (dual SIM).
     * IMEI encoded as BCD (Binary Coded Decimal) in 9-byte structure.
     * Based on QPST/QXDM protocol: CMD_CODE 39 (NV Write).
     */
    fun tryQualcommDiagNv(imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Qualcomm DIAG NV550 direct write...")
        return try {
            if (!RootUtil.isValidImei(imei1)) return false

            // Activate DIAG mode
            RootUtil.executeRootCommand("setprop sys.usb.config diag,adb")
            Thread.sleep(500)

            // Check for DIAG device
            val diagDevices = arrayOf("/dev/diag", "/dev/qcom_diag", "/dev/ttyGS0")
            var diagDev: String? = null
            for (dev in diagDevices) {
                if (RootUtil.fileExists(dev)) {
                    diagDev = dev
                    break
                }
            }

            if (diagDev != null) {
                Log.d(TAG, "DIAG device found: $diagDev")
                // NV Write command: 0x27 (CMD_CODE 39) + NV Item ID (2 bytes LE) + Data (128 bytes)
                // NV550 = 0x0226, NV551 = 0x0227
                val nv550packet = buildNvWritePacket(550, encodeImeiToBcd(imei1))
                val nv551packet = buildNvWritePacket(551, encodeImeiToBcd(imei2))

                val w1 = RootUtil.writeHexToFile(nv550packet, diagDev)
                val w2 = RootUtil.writeHexToFile(nv551packet, diagDev)

                // Reset USB config
                RootUtil.executeRootCommand("setprop sys.usb.config adb")

                if (w1 || w2) {
                    Log.i(TAG, "DIAG NV write sent successfully")
                    return true
                }
            }

            // Alternative: QMI-based write via qmicli if available
            val qmiAvailable = RootUtil.fileExists("/usr/bin/qmicli") ||
                    RootUtil.fileExists("/system/bin/qmicli")
            if (qmiAvailable) {
                val bcd = encodeImeiForQmi(imei1)
                val res = RootUtil.executeWithOutput(
                    "qmicli -d qrtr://0 --dms-set-imei=$bcd"
                )
                if (res.contains("success", ignoreCase = true)) return true
            }

            // Reset USB config
            RootUtil.executeRootCommand("setprop sys.usb.config adb")
            false
        } catch (e: Exception) {
            RootUtil.executeRootCommand("setprop sys.usb.config adb")
            Log.e(TAG, "Qualcomm DIAG NV550 failed: ${e.message}")
            false
        }
    }

    /**
     * Encode IMEI to BCD format for NV Item 550.
     * Format: length(1) + BCD digits(8) = 9 bytes.
     * IMEI "123456789012345" → {0x08, 0x1A, 0x32, 0x54, 0x76, 0x98, 0x10, 0x32, 0x04}
     */
    private fun encodeImeiToBcd(imei: String): ByteArray {
        val bcd = ByteArray(9)
        bcd[0] = 0x08 // length always 8
        // First digit + 0xA nibble
        bcd[1] = ((imei[0].digitToInt() and 0x0F) or 0xA0).toByte()
        // Remaining 14 digits packed as BCD pairs
        var idx = 2
        var i = 1
        while (i < 15 && idx < 9) {
            val low = imei[i].digitToInt() and 0x0F
            val high = if (i + 1 < 15) (imei[i + 1].digitToInt() and 0x0F) else 0
            bcd[idx] = ((high shl 4) or low).toByte()
            i += 2
            idx++
        }
        return bcd
    }

    private fun buildNvWritePacket(nvItem: Int, data: ByteArray): String {
        // CMD_CODE 39 (0x27) + NV Item ID (2 bytes LE) + 128 bytes data (zero-padded)
        val sb = StringBuilder()
        sb.append("\\x27") // CMD_CODE = NV_WRITE
        sb.append("\\x${String.format("%02x", nvItem and 0xFF)}") // Item ID low byte
        sb.append("\\x${String.format("%02x", (nvItem shr 8) and 0xFF)}") // Item ID high byte
        for (b in data) {
            sb.append("\\x${String.format("%02x", b.toInt() and 0xFF)}")
        }
        // Pad to 128 bytes data
        for (i in data.size until 128) {
            sb.append("\\x00")
        }
        return sb.toString()
    }

    private fun encodeImeiForQmi(imei: String): String {
        return imei // QMI accepts plain string
    }

    // ======================== GOOGLE PIXEL DEVINFO ========================

    /**
     * Google Pixel devinfo partition direct write.
     * Based on lexipwn (Codeberg) — works on Pixel 6/7/8/9/10 (Tensor G1-G5 SoC).
     * IMEI stored in PS tags within /dev/block/by-name/devinfo partition.
     */
    fun tryPixelDevinfo(imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Pixel devinfo partition IMEI write...")
        return try {
            if (!RootUtil.isValidImei(imei1) || !RootUtil.isValidImei(imei2)) return false

            // Find devinfo partition
            val devinfoPaths = arrayOf(
                "/dev/block/by-name/devinfo",
                "/dev/block/bootdevice/by-name/devinfo",
                "/dev/block/platform/*/by-name/devinfo"
            )
            var devinfoPath: String? = null
            for (path in devinfoPaths) {
                val resolved = RootUtil.executeWithOutput("ls $path 2>/dev/null")
                if (resolved.isNotEmpty()) {
                    devinfoPath = resolved.trim()
                    break
                }
            }
            if (devinfoPath == null) {
                Log.w(TAG, "devinfo partition not found")
                return false
            }

            Log.d(TAG, "devinfo partition: $devinfoPath")

            // Backup devinfo
            RootUtil.executeRootCommand("dd if=$devinfoPath of=/sdcard/devinfo_consul.bak bs=4096")

            // Read current devinfo
            val hexDump = RootUtil.executeWithOutput(
                "dd if=$devinfoPath bs=4096 count=1 2>/dev/null | xxd -p"
            )
            if (hexDump.isEmpty()) {
                Log.w(TAG, "Failed to read devinfo")
                return false
            }

            // Find IMEI tag pattern in devinfo (ASCII "IMEI" = 494d4549)
            val imeiTagHex = "494d4549" // "IMEI" in hex
            if (!hexDump.contains(imeiTagHex, ignoreCase = true)) {
                Log.w(TAG, "IMEI tag not found in devinfo, trying offset-based method")
                // Fallback: write IMEI at known offsets using sed
                val imei1Hex = imei1.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
                val imei2Hex = imei2.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }

                // Create modified devinfo
                RootUtil.executeRootCommand(
                    "dd if=$devinfoPath of=/tmp/devinfo.img bs=4096"
                )
                // Read current IMEI via root service call
                val currentImei1 = RootUtil.executeWithOutput(
                    "service call iphonesubinfo 1 | grep -oP \"'[0-9.]+\" | tr -d \"'.\" | head -c 15"
                ).trim()
                if (currentImei1.length == 15) {
                    val curHex = currentImei1.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
                    RootUtil.executeRootCommand(
                        "xxd -p /tmp/devinfo.img | sed 's/$curHex/$imei1Hex/g' | xxd -r -p > /tmp/devinfo_new.img"
                    )
                    RootUtil.executeRootCommand(
                        "dd if=/tmp/devinfo_new.img of=$devinfoPath bs=4096"
                    )
                    Log.i(TAG, "Pixel devinfo written")
                    return true
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Pixel devinfo failed: ${e.message}")
            false
        }
    }

    // ======================== MTK NVRAM MP0B_001 (LEGACY) ========================

    /**
     * Direct NVRAM file write for legacy MediaTek devices.
     * Based on chuacw/WriteIMEI — creates MP0B_001 file with XOR-encoded IMEI.
     * Works on MTK6515, MT6572, MT6580, MT6589, MT6592, MT6735, MT6737, MT6750, MT6753.
     */
    fun tryMtkNvramDirect(imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting MTK NVRAM MP0B_001 direct write...")
        return try {
            if (!RootUtil.isValidImei(imei1)) return false

            val nvramPaths = arrayOf(
                "/data/nvram/md/NVRAM/NVD_IMEI/MP0B_001",
                "/nvram/md/NVRAM/NVD_IMEI/MP0B_001",
                "/vendor/nvdata/md/NVRAM/NVD_IMEI/MP0B_001",
                "/mnt/vendor/nvdata/md/NVRAM/NVD_IMEI/MP0B_001"
            )

            var targetPath: String? = null
            for (path in nvramPaths) {
                if (RootUtil.fileExists(path)) {
                    targetPath = path
                    break
                }
            }

            // If no existing file, try to find the directory
            if (targetPath == null) {
                val dirs = arrayOf(
                    "/data/nvram/md/NVRAM/NVD_IMEI",
                    "/nvram/md/NVRAM/NVD_IMEI",
                    "/vendor/nvdata/md/NVRAM/NVD_IMEI"
                )
                for (dir in dirs) {
                    if (RootUtil.fileExists(dir)) {
                        targetPath = "$dir/MP0B_001"
                        break
                    }
                }
            }

            if (targetPath == null) {
                Log.w(TAG, "No NVRAM IMEI directory found")
                return false
            }

            Log.d(TAG, "NVRAM target: $targetPath")

            // Backup existing
            RootUtil.backupFile(targetPath)

            // Encode IMEI to MP0B_001 format (XOR mask based on chuacw/WriteIMEI)
            val encodedHex = encodeImeiForMp0b001(imei1, imei2)

            // Write encoded data
            val success = RootUtil.writeHexToFile(encodedHex, targetPath)
            if (success) {
                // Set permissions
                RootUtil.executeRootCommand("chmod 644 $targetPath")
                RootUtil.executeRootCommand("chown root:nvram $targetPath 2>/dev/null")
                Log.i(TAG, "MTK NVRAM MP0B_001 written successfully")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "MTK NVRAM direct write failed: ${e.message}")
            false
        }
    }

    /**
     * Encode IMEI pair to MP0B_001 binary format.
     * XOR mask from chuacw: {0xAB, 0xA0, 0x6F, 0x2F, 0x1F, 0x1E, 0x9A, 0x45, 0x0, 0x0, 0x0, 0x0}
     */
    private fun encodeImeiForMp0b001(imei1: String, imei2: String): String {
        val xorMask = byteArrayOf(
            0xAB.toByte(), 0xA0.toByte(), 0x6F, 0x2F, 0x1F, 0x1E,
            0x9A.toByte(), 0x45, 0x00, 0x00, 0x00, 0x00
        )

        fun encodeOneImei(imei: String): ByteArray {
            val out = ByteArray(12)
            var j = 0
            var i = 0
            while (i < 15 && j < 8) {
                val low = imei[i].digitToInt()
                val high = if (i + 1 < 15) imei[i + 1].digitToInt() else 0
                out[j] = ((low or (high shl 4)) xor xorMask[j].toInt()).toByte()
                i += 2
                j++
            }
            out[8] = 0x57
            out[9] = 0xDB.toByte()
            // Checksums
            var sum10 = 0
            var sum11 = 0
            for (k in 0 until 10) {
                if (k and 1 == 1) sum11 += out[k].toInt() and 0xFF
                else sum10 += out[k].toInt() and 0xFF
            }
            out[10] = (sum10 and 0xFF).toByte()
            out[11] = (sum11 and 0xFF).toByte()
            return out
        }

        val encoded1 = encodeOneImei(imei1)
        val encoded2 = if (RootUtil.isValidImei(imei2)) encodeOneImei(imei2) else encoded1

        val sb = StringBuilder()
        for (b in encoded1) sb.append("\\x${String.format("%02x", b.toInt() and 0xFF)}")
        for (b in encoded2) sb.append("\\x${String.format("%02x", b.toInt() and 0xFF)}")
        return sb.toString()
    }

    // ======================== MTK NVRAM HIDL/AIDL ========================

    /**
     * Write IMEI via MediaTek's NVRAM HAL (vendor.mediatek.hardware.nvram@1.0).
     * Available on Android 8+ MTK devices. Writes to NVRAM through vendor HAL service.
     */
    fun tryMtkNvramHal(imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting MTK NVRAM HAL write...")
        return try {
            // Try HIDL INvram service
            val nvramClass = Class.forName("vendor.mediatek.hardware.nvram.V1_0.INvram")
            val getService = nvramClass.getMethod("getService", Boolean::class.java)
            val nvramService = getService.invoke(null, true) ?: return false

            Log.d(TAG, "NVRAM HAL service found")

            // Read current NVRAM to find IMEI offset
            val readMethod = nvramClass.getMethod("readFileByName", String::class.java, Int::class.java)
            val nvramFile = "/mnt/vendor/nvdata/md/NVRAM/NVD_IMEI/MP0B_001"
            val currentData = readMethod.invoke(nvramService, nvramFile, 24) as? java.util.ArrayList<*>

            if (currentData != null && currentData.size > 0) {
                // Prepare new data with encoded IMEI
                val encoded = encodeImeiForMp0b001(imei1, imei2)
                val hexBytes = encoded.replace("\\x", "")
                val newData = java.util.ArrayList<Byte>()
                for (i in hexBytes.indices step 2) {
                    newData.add(hexBytes.substring(i, i + 2).toInt(16).toByte())
                }

                // Write via HAL
                val writeMethod = nvramClass.getMethod(
                    "writeFileByNamevec", String::class.java, Int::class.java, java.util.ArrayList::class.java
                )
                val result = writeMethod.invoke(nvramService, nvramFile, newData.size, newData) as? Int
                if (result != null && result > 0) {
                    Log.i(TAG, "MTK NVRAM HAL write successful: $result bytes")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.v(TAG, "MTK NVRAM HAL not available: ${e.message}")
            false
        }
    }

    // ======================== SAMSUNG EFS ========================

    /**
     * Samsung EFS partition direct write.
     * Samsung stores IMEI in /efs/FactoryApp/imei, /efs/imei/mps_code.dat
     * and QCN-format NV items in EFS.
     */
    fun trySamsungEfs(imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Samsung EFS partition write...")
        return try {
            if (!RootUtil.isValidImei(imei1) || !RootUtil.isValidImei(imei2)) return false

            val efsPaths = arrayOf(
                "/efs/FactoryApp/imei" to "/efs/FactoryApp/imei2",
                "/efs/imei/mps_code.dat" to "/efs/imei/mps_code2.dat",
                "/efs/FactoryApp/serial_no" to "/efs/FactoryApp/serial_no2",
                "/persist/radio/imei" to "/persist/radio/imei2",
                "/mnt/vendor/efs/FactoryApp/imei" to "/mnt/vendor/efs/FactoryApp/imei2"
            )

            var success = false
            for ((path1, path2) in efsPaths) {
                if (RootUtil.fileExists(path1)) {
                    Log.d(TAG, "Samsung EFS path found: $path1")
                    RootUtil.backupFile(path1)
                    if (RootUtil.fileExists(path2)) RootUtil.backupFile(path2)

                    val w1 = RootUtil.executeRootCommand("echo -n '$imei1' > $path1")
                    val w2 = RootUtil.executeRootCommand("echo -n '$imei2' > $path2")
                    if (w1 || w2) {
                        RootUtil.executeRootCommand("chmod 644 $path1")
                        if (RootUtil.fileExists(path2)) RootUtil.executeRootCommand("chmod 644 $path2")
                        success = true
                        Log.i(TAG, "Samsung EFS written: $path1")
                    }
                }
            }

            // Also try nv_data.bin offset method
            if (!success) {
                val nvDataPaths = arrayOf(
                    "/efs/nv_data.bin",
                    "/mnt/vendor/efs/nv_data.bin"
                )
                for (nvPath in nvDataPaths) {
                    if (RootUtil.fileExists(nvPath)) {
                        RootUtil.backupFile(nvPath)
                        // IMEI is at offset 0x186E0 in Samsung nv_data.bin (varies by model)
                        val imeiHex = imei1.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
                        val written = RootUtil.executeRootCommand(
                            "printf '${imeiHex}' | xxd -r -p | dd of=$nvPath bs=1 seek=99040 conv=notrunc"
                        )
                        if (written) {
                            success = true
                            Log.i(TAG, "Samsung nv_data.bin IMEI written at offset")
                        }
                    }
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Samsung EFS failed: ${e.message}")
            false
        }
    }

    // ======================== ONEPLUS / NOTHING ========================

    /**
     * OnePlus/Nothing devices — use Qualcomm SoC with OxygenOS/Nothing OS.
     * Custom telephony service + DIAG + persist paths.
     */
    fun tryOnePlus(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting OnePlus/Nothing IMEI write...")
        return try {
            // 1. OnePlus-specific EngineerMode
            val engineerIntents = arrayOf(
                "com.oneplus.factorymode.ACTION_AT_COMMAND",
                "com.oplus.engineermode.ACTION_AT_COMMAND",
                "com.nothing.factorytest.ACTION_AT_COMMAND"
            )
            for (action in engineerIntents) {
                try {
                    val intent = Intent(action)
                    intent.putExtra("at_command", "AT+EGMR=1,7,\"$imei1\"")
                    intent.putExtra("slot_id", 0)
                    context.sendBroadcast(intent)
                } catch (_: Exception) {}
            }

            // 2. Standard AT via RIL
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 3. OnePlus vendor telephony service
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val oneplusServices = arrayOf(
                "vendor.qti.hardware.radio.qtiradio.IQtiRadioStable/slot1",
                "oplus_telephony",
                "opphone"
            )
            for (svc in oneplusServices) {
                try {
                    val binder = getService.invoke(null, svc) as? IBinder ?: continue
                    val proxy = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder) ?: continue
                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("invokeOemRilRequest")) {
                            m.isAccessible = true
                            val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                            m.invoke(proxy, cmd)
                            return true
                        }
                    }
                } catch (_: Exception) {}
            }

            // 4. Qualcomm persist paths (OnePlus/Nothing)
            if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
                val persistPaths = arrayOf(
                    "/mnt/vendor/persist/radio/imei",
                    "/persist/radio/imei",
                    "/op2/modem/imei"
                )
                for (path in persistPaths) {
                    if (RootUtil.fileExists(path)) {
                        RootUtil.backupFile(path)
                        if (RootUtil.executeRootCommand("echo -n '$imei1' > $path")) {
                            return true
                        }
                    }
                }
            }

            // 5. DIAG mode
            if (tryQualcommDiagNv(imei1, imei2)) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "OnePlus/Nothing failed: ${e.message}")
            false
        }
    }

    // ======================== VIVO / IQOO ========================

    /**
     * Vivo/iQOO devices — Qualcomm or MTK with FuntouchOS/OriginOS.
     */
    fun tryVivo(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Vivo/iQOO IMEI write...")
        return try {
            // 1. Vivo-specific AT commands
            val vivoAtCmds = arrayOf(
                "AT+EGMR=1,7,\"$imei1\"",
                "AT+VIVOEGMR=1,7,\"$imei1\""
            )
            for (cmd in vivoAtCmds) {
                val res = OemRilUtil.sendAtCommand(context, cmd, 0)
                if (res.contains("OK")) {
                    OemRilUtil.sendAtCommand(context, cmd.replace(",7,", ",10,").replace(imei1, imei2), 1)
                    return true
                }
            }

            // 2. Vivo EngineerMode broadcast
            val engineerIntents = arrayOf(
                "com.vivo.engineermode.ACTION_AT_COMMAND",
                "com.iqoo.engineermode.ACTION_AT_COMMAND"
            )
            for (action in engineerIntents) {
                try {
                    val intent = Intent(action)
                    intent.putExtra("at_command", "AT+EGMR=1,7,\"$imei1\"")
                    context.sendBroadcast(intent)
                } catch (_: Exception) {}
            }

            // 3. Vivo telephony service
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val vivoServices = arrayOf("vivo_telephony", "iqoo_phone")
            for (svc in vivoServices) {
                try {
                    val binder = getService.invoke(null, svc) as? IBinder ?: continue
                    val proxy = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder) ?: continue
                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("invokeOemRilRequest") || m.name.contains("sendOemRilRequest")) {
                            m.isAccessible = true
                            val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                            m.invoke(proxy, cmd)
                            return true
                        }
                    }
                } catch (_: Exception) {}
            }

            // 4. CPU-based fallback
            val cpuType = CpuUtil.getCpuType()
            if (cpuType == CpuUtil.CpuType.MEDIATEK) {
                if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) return true
                if (tryMtkNvramDirect(imei1, imei2)) return true
            } else if (cpuType == CpuUtil.CpuType.QUALCOMM) {
                if (tryQualcommDiagNv(imei1, imei2)) return true
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Vivo/iQOO failed: ${e.message}")
            false
        }
    }

    // ======================== MOTOROLA / LENOVO ========================

    /**
     * Motorola/Lenovo devices — Qualcomm SoC with near-stock Android.
     */
    fun tryMotorola(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Motorola/Lenovo IMEI write...")
        return try {
            // 1. Standard AT commands
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 2. Motorola-specific persist paths
            if (RootUtil.isValidImei(imei1)) {
                val motoPaths = arrayOf(
                    "/persist/radio/imei",
                    "/mnt/vendor/persist/radio/imei",
                    "/persist/factory/imei"
                )
                for (path in motoPaths) {
                    if (RootUtil.fileExists(path)) {
                        RootUtil.backupFile(path)
                        if (RootUtil.executeRootCommand("echo -n '$imei1' > $path")) {
                            return true
                        }
                    }
                }
            }

            // 3. DIAG fallback
            if (tryQualcommDiagNv(imei1, imei2)) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "Motorola/Lenovo failed: ${e.message}")
            false
        }
    }

    // ======================== NOKIA / HMD ========================

    /**
     * Nokia/HMD devices — near-stock Android One with Qualcomm or MTK.
     */
    fun tryNokia(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Nokia/HMD IMEI write...")
        return try {
            val cpuType = CpuUtil.getCpuType()

            // 1. Standard AT commands
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 2. CPU-specific fallback
            if (cpuType == CpuUtil.CpuType.MEDIATEK) {
                if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) return true
                if (tryMtkNvramDirect(imei1, imei2)) return true
                if (tryMtkNvramHal(imei1, imei2)) return true
            } else if (cpuType == CpuUtil.CpuType.QUALCOMM) {
                if (tryQualcommDiagNv(imei1, imei2)) return true
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Nokia/HMD failed: ${e.message}")
            false
        }
    }

    // ======================== ZTE / NUBIA ========================

    fun tryZte(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting ZTE/Nubia IMEI write...")
        return try {
            // ZTE-specific AT command
            val res1 = OemRilUtil.sendAtCommand(context, "AT+ZTEIMEI=1,\"$imei1\"", 0)
            if (res1.contains("OK")) {
                OemRilUtil.sendAtCommand(context, "AT+ZTEIMEI=2,\"$imei2\"", 1)
                return true
            }

            // Standard fallback
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res3 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res2.contains("OK") || res3.contains("OK")) return true

            if (tryQualcommDiagNv(imei1, imei2)) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "ZTE/Nubia failed: ${e.message}")
            false
        }
    }

    // ======================== MEIZU ========================

    fun tryMeizu(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Meizu IMEI write...")
        return try {
            // Meizu uses MTK or Qualcomm
            val cpuType = CpuUtil.getCpuType()

            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            if (cpuType == CpuUtil.CpuType.MEDIATEK) {
                if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) return true
                if (tryMtkNvramDirect(imei1, imei2)) return true
            } else {
                if (tryQualcommDiagNv(imei1, imei2)) return true
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Meizu failed: ${e.message}")
            false
        }
    }

    // ======================== SNAPDRAGON 8 GEN 4 / 8s GEN 4 (2025-2026) ========================

    /**
     * Snapdragon 8 Gen 4 (SM8750, codename "Niobe") — 2026 flagship.
     * Snapdragon 8s Gen 4 (SM8735, codename "Blair") — 2026 sub-flagship.
     * Snapdragon 7+ Gen 4 (SM7675, codename "Volcano") — 2026 mid-premium.
     *
     * These chips use AIDL v3 IRadioModem with enhanced security:
     * - Secure Element (SE) backed NV storage
     * - TEE-verified AT command channel
     * - Anti-rollback counters for IMEI
     *
     * Methods: AIDL v3 IRadioModem → QMI direct → DIAG → persist file → service call
     */
    fun trySnapdragonGen4(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Snapdragon 8 Gen 4 / 8s Gen 4 IMEI write...")
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            // 1. AIDL v3 IRadioModem (new in Gen 4)
            val gen4Services = arrayOf(
                "vendor.qti.hardware.radio.modem.IRadioModem/slot1",
                "vendor.qti.hardware.radio.qtiradio.IQtiRadioStable/slot1",
                "vendor.qti.hardware.radio.qtiradio.IQtiRadio/slot1",
                "vendor.qti.hardware.radio.internal.IRadioModemInternal/slot1",
                "vendor.qti.hardware.radio.modem.V3_0.IRadioModem/slot1",
                "vendor.qti.hardware.radio.modem.V2_0.IRadioModem/slot1",
                "qti.radio.slot1"
            )

            for (serviceName in gen4Services) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    Log.d(TAG, "Gen 4 service found: $serviceName")

                    // Try to get proxy via Stub.asInterface
                    val stubClassName = serviceName.substringBefore("/") + "\$Stub"
                    try {
                        val stubClass = Class.forName(stubClassName)
                        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                        val proxy = asInterface.invoke(null, binder) ?: continue

                        for (m in proxy.javaClass.methods) {
                            if (m.name.contains("sendOemRilRequest") ||
                                m.name.contains("invokeOemRilRequest") ||
                                m.name.contains("sendAtCommand") ||
                                m.name.contains("sendModemCommand")) {
                                try {
                                    m.isAccessible = true
                                    val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                    m.invoke(proxy, cmd)
                                    Log.d(TAG, "Gen 4 invoked via ${m.name}")

                                    // Write SIM2
                                    val cmd2 = "AT+EGMR=1,10,\"$imei2\"\r\u0000".toByteArray()
                                    m.invoke(proxy, cmd2)
                                    return true
                                } catch (e: Exception) {
                                    Log.w(TAG, "Gen 4 method ${m.name} failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: ClassNotFoundException) {
                        // Stub class not available, try direct transact
                        Log.d(TAG, "No stub for $stubClassName, trying direct transact")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Gen 4 service $serviceName error: ${e.message}")
                }
            }

            // 2. QMI direct via /dev/qmi_* (Gen 4 exposes new QMI endpoints)
            val qmiDevices = arrayOf(
                "/dev/qmi0", "/dev/qmi1", "/dev/qmi2",
                "/dev/diag", "/dev/ccci_ioctl"
            )
            if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
                for (dev in qmiDevices) {
                    if (RootUtil.fileExists(dev)) {
                        Log.d(TAG, "QMI device found: $dev")
                        val r = RootUtil.executeRootCommand(
                            "echo 'AT+EGMR=1,7,\"$imei1\"' > $dev 2>/dev/null"
                        )
                        if (r) {
                            RootUtil.executeRootCommand(
                                "echo 'AT+EGMR=1,10,\"$imei2\"' > $dev 2>/dev/null"
                            )
                            return true
                        }
                    }
                }
            }

            // 3. Enhanced persist paths for Gen 4 (new vendor partition layout)
            if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
                val gen4PersistPaths = arrayOf(
                    "/mnt/vendor/persist/radio/imei",
                    "/mnt/vendor/persist/radio/imei1",
                    "/mnt/vendor/persist/data/imei",
                    "/persist/radio/imei",
                    "/persist/radio/imei1",
                    "/vendor/persist/radio/imei",
                    "/mnt/vendor/nv/radio/imei",
                    "/mnt/vendor/modemst1/imei",
                    "/mnt/vendor/modemst2/imei"
                )
                for (path in gen4PersistPaths) {
                    if (RootUtil.fileExists(path)) {
                        RootUtil.backupFile(path)
                        if (RootUtil.executeRootCommand("echo -n '$imei1' > $path && chmod 644 $path")) {
                            Log.i(TAG, "Gen 4 persist write OK: $path")
                            RootUtil.executeRootCommand("setprop sys.radio.restart 1")
                            return true
                        }
                    }
                }
            }

            // 4. Standard AT+EGMR via RIL
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 5. DIAG NV fallback
            if (tryQualcommDiagNv(imei1, imei2)) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "Snapdragon Gen 4 failed: ${e.message}")
            false
        }
    }

    // ======================== DIMENSITY 9400+ / 9500 / 8400 (2025-2026) ========================

    /**
     * MediaTek Dimensity 9500 (MT6991) — 2026 flagship, TSMC 2nm.
     * MediaTek Dimensity 9400+ (MT6989P) — 2025-2026 enhanced flagship.
     * MediaTek Dimensity 8400 (MT6990) — 2025-2026 sub-flagship.
     *
     * These use AIDL v2+ RadioEx with enhanced IMtkRadioExModem interface.
     * Key difference: new HAL service names and namespace changes.
     */
    fun tryDimensity2026(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Dimensity 9400+/9500/8400 IMEI write...")
        return try {
            // 1. AIDL v2+ RadioEx (primary for 9400+/9500/8400)
            if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) {
                Log.i(TAG, "Dimensity 2026: RadioEx AIDL succeeded")
                return true
            }

            // 2. Enhanced vendor service names (9500/8400 new HAL)
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            val dimServices = arrayOf(
                "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/slot1",
                "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/mtkSlot1",
                "vendor.mediatek.hardware.mtkradioex.modem.V2_0.IMtkRadioExModem/slot1",
                "vendor.mediatek.hardware.mtkradioex.modem.V3_0.IMtkRadioExModem/slot1",
                "vendor.mediatek.hardware.radio.modem.IRadioModem/slot1",
                "vendor.mediatek.hardware.radio.modem.V2_0.IRadioModem/slot1",
                "mtkRadioEx1",
                "mtkRadioExModem1"
            )

            for (serviceName in dimServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    val descriptor = serviceName.substringBefore("/")
                    val stubClass = Class.forName("$descriptor\$Stub")
                    val proxy = stubClass.getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder) ?: continue

                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("sendRequestStrings") || m.name.contains("sendAtCommand") ||
                            m.name.contains("sendModemCommand")) {
                            try {
                                m.isAccessible = true
                                val cmd = arrayOf("AT+EGMR=1,7,\"$imei1\"", "")
                                m.invoke(proxy, 7, cmd, 3)
                                Log.i(TAG, "Dimensity 2026 write via ${m.name}")
                                return true
                            } catch (e: Exception) {
                                Log.w(TAG, "Dimensity 2026 ${m.name} error: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Dimensity 2026 service $serviceName: ${e.message}")
                }
            }

            // 3. Classic AT commands
            val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
            val res2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)
            if (res1.contains("OK") || res2.contains("OK")) return true

            // 4. NVRAM HAL
            if (tryMtkNvramHal(imei1, imei2)) return true

            // 5. NVRAM direct
            if (tryMtkNvramDirect(imei1, imei2)) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "Dimensity 2026 failed: ${e.message}")
            false
        }
    }

    // ======================== EXYNOS 2500 / 2600 (2025-2026) ========================

    /**
     * Samsung Exynos 2500 (S5E9955) — Galaxy S26 series, 2nm GAA.
     * Samsung Exynos 2400 (S5E9945) — Galaxy S25 series.
     * Enhanced Samsung RIL with new vendor extensions.
     */
    fun trySamsungExynos2026(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Samsung Exynos 2500/2600 IMEI write...")
        return try {
            // 1. Samsung-specific AT via ServiceMode
            if (trySamsungMsl(context, imei1, imei2)) return true

            // 2. Standard Exynos method
            if (trySamsungExynos(context, imei1, imei2)) return true

            // 3. Enhanced Samsung vendor HAL (Exynos 2500+)
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            val exynosServices = arrayOf(
                "vendor.samsung.hardware.radio.modem.ISehRadioModem/slot1",
                "vendor.samsung.hardware.radio.ISehRadio/slot1",
                "vendor.samsung.hardware.radio.V2_0.ISehRadio/slot1",
                "sec_phone"
            )
            for (serviceName in exynosServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    Log.d(TAG, "Exynos 2500 service: $serviceName")

                    val stubClassName = serviceName.substringBefore("/") + "\$Stub"
                    val stubClass = Class.forName(stubClassName)
                    val proxy = stubClass.getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder) ?: continue

                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("sendOemRilRequest") || m.name.contains("invokeOemRilRequest") ||
                            m.name.contains("sendSehRequest")) {
                            try {
                                m.isAccessible = true
                                val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                m.invoke(proxy, cmd)
                                return true
                            } catch (e: Exception) { /* continue */ }
                        }
                    }
                } catch (e: Exception) { /* try next */ }
            }

            // 4. EFS partition
            if (trySamsungEfs(imei1, imei2)) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "Exynos 2500/2600 failed: ${e.message}")
            false
        }
    }

    // ======================== SNAPDRAGON 8 ELITE (SM8850) — 2026 ========================

    /**
     * Qualcomm Snapdragon 8 Elite (SM8850) — 2026 flagship, TSMC 2nm.
     * Uses AIDL v4 IRadioModem with enhanced security attestation.
     * New QMI v3 protocol with signed command payloads.
     */
    fun trySnapdragonElite(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Snapdragon 8 Elite (SM8850) IMEI write...")
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            // AIDL v4 services (SM8850-specific)
            val eliteServices = arrayOf(
                "vendor.qti.hardware.radio.modem.IRadioModem/slot1",
                "vendor.qti.hardware.radio.modem.V4_0.IRadioModem/slot1",
                "vendor.qti.hardware.radio.modem.V3_0.IRadioModem/slot1",
                "vendor.qti.hardware.radio.qtiradio.IQtiRadioStable/slot1",
                "vendor.qti.hardware.radio.internal.IRadioModemInternal/slot1",
                "qti.radio.slot1"
            )

            for (serviceName in eliteServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    Log.d(TAG, "SM8850 service: $serviceName")

                    val stubClassName = serviceName.substringBefore("/") + "\$Stub"
                    try {
                        val stubClass = Class.forName(stubClassName)
                        val proxy = stubClass.getMethod("asInterface", IBinder::class.java)
                            .invoke(null, binder) ?: continue

                        for (m in proxy.javaClass.methods) {
                            if (m.name.contains("sendOemRilRequest") ||
                                m.name.contains("invokeOemRilRequest") ||
                                m.name.contains("sendAtCommand") ||
                                m.name.contains("sendModemCommand") ||
                                m.name.contains("sendQmiRequest")) {
                                try {
                                    m.isAccessible = true
                                    val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                    m.invoke(proxy, cmd)

                                    val cmd2 = "AT+EGMR=1,10,\"$imei2\"\r\u0000".toByteArray()
                                    m.invoke(proxy, cmd2)
                                    Log.i(TAG, "SM8850 IMEI write via ${m.name}")
                                    return true
                                } catch (e: Exception) {
                                    Log.w(TAG, "SM8850 ${m.name} failed: ${e.message}")
                                }
                            }
                        }
                    } catch (_: ClassNotFoundException) {
                        // Try direct transact fallback
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SM8850 service $serviceName: ${e.message}")
                }
            }

            // Fallback to Gen 4 method chain
            if (trySnapdragonGen4(context, imei1, imei2)) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "SM8850 failed: ${e.message}")
            false
        }
    }

    // ======================== EXYNOS 2700 (S5E9965) — 2026 ========================

    /**
     * Samsung Exynos 2700 (S5E9965) — Galaxy S27 series, Samsung 2nm SF2 process.
     * New generation Samsung modem with enhanced SEH (Samsung Enhanced HAL).
     */
    fun tryExynos2700(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Exynos 2700 (S5E9965) IMEI write...")
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            val exynosServices = arrayOf(
                "vendor.samsung.hardware.radio.modem.ISehRadioModem/slot1",
                "vendor.samsung.hardware.radio.modem.V2_0.ISehRadioModem/slot1",
                "vendor.samsung.hardware.radio.ISehRadio/slot1",
                "vendor.samsung.hardware.radio.V3_0.ISehRadio/slot1",
                "sec_phone"
            )

            for (serviceName in exynosServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    Log.d(TAG, "Exynos 2700 service: $serviceName")
                    val stubClassName = serviceName.substringBefore("/") + "\$Stub"
                    val stubClass = Class.forName(stubClassName)
                    val proxy = stubClass.getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder) ?: continue

                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("sendOemRilRequest") ||
                            m.name.contains("invokeOemRilRequest") ||
                            m.name.contains("sendSehRequest") ||
                            m.name.contains("sendModemCommand")) {
                            try {
                                m.isAccessible = true
                                val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                m.invoke(proxy, cmd)
                                val cmd2 = "AT+EGMR=1,10,\"$imei2\"\r\u0000".toByteArray()
                                m.invoke(proxy, cmd2)
                                Log.i(TAG, "Exynos 2700 write via ${m.name}")
                                return true
                            } catch (e: Exception) { /* continue */ }
                        }
                    }
                } catch (e: Exception) { /* try next */ }
            }

            // Fallback chain
            if (trySamsungExynos2026(context, imei1, imei2)) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "Exynos 2700 failed: ${e.message}")
            false
        }
    }

    // ======================== TENSOR G5 / G6 (2025-2026) ========================

    /**
     * Google Tensor G5 (GS501/GS601) — Pixel 11/12 series.
     * TSMC 3nm/2nm. Completely new modem (Samsung→MediaTek transition rumored).
     */
    fun tryTensorG5G6(context: Context, imei1: String, imei2: String): Boolean {
        Log.d(TAG, "Attempting Tensor G5/G6 IMEI write...")
        return try {
            // 1. Standard Tensor method
            if (tryGoogleTensor(context, imei1, imei2)) return true

            // 2. Pixel devinfo partition
            if (tryPixelDevinfo(imei1, imei2)) return true

            // 3. New Tensor G5/G6 vendor HAL (may use MTK modem HAL)
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            val tensorServices = arrayOf(
                "vendor.google.hardware.radio.modem.IRadioModem/slot1",
                "vendor.google.hardware.radio.V2_0.IRadioModem/slot1",
                "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/slot1",
                "google.radio.slot1"
            )
            for (serviceName in tensorServices) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    val stubClassName = serviceName.substringBefore("/") + "\$Stub"
                    val stubClass = Class.forName(stubClassName)
                    val proxy = stubClass.getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder) ?: continue

                    for (m in proxy.javaClass.methods) {
                        if (m.name.contains("sendOemRilRequest") || m.name.contains("invokeOemRilRequest") ||
                            m.name.contains("sendRequestStrings")) {
                            try {
                                m.isAccessible = true
                                val cmd = "AT+EGMR=1,7,\"$imei1\"\r\u0000".toByteArray()
                                m.invoke(proxy, cmd)
                                return true
                            } catch (e: Exception) { /* continue */ }
                        }
                    }
                } catch (e: Exception) { /* try next */ }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Tensor G5/G6 failed: ${e.message}")
            false
        }
    }
}
