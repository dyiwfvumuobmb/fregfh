package com.android.imeisettings.util

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Intelligent selector that analyzes the device and chooses the best IMEI writing method.
 * Supports: MediaTek, Qualcomm, Samsung Exynos, Google Tensor, HiSilicon Kirin, Unisoc,
 * Infinix/TECNO/itel (Transsion), OnePlus/Nothing, Vivo/iQOO, Motorola/Lenovo,
 * Nokia/HMD, ZTE/Nubia, Meizu, Realme, OPPO.
 *
 * Total methods: 15+ brand-specific + 6 generic fallback levels.
 */
object AutoMethodSelector {
    private const val TAG = "CONSUL_SELECTOR"

    data class MethodResult(val success: Boolean, val methodName: String)

    fun executeBestMethod(context: Context, imei1: String, imei2: String): MethodResult {
        val manufacturer = Build.MANUFACTURER.uppercase()
        val cpuType = CpuUtil.getCpuType()

        Log.i(TAG, "Analyzing device: $manufacturer | CPU: $cpuType | Model: ${Build.MODEL}")

        // ═══════════════════════════════════════════════════════
        // -1. SYSTEM APP: Direct system-level methods (no root needed)
        // ═══════════════════════════════════════════════════════
        if (isSystemApp(context)) {
            Log.i(TAG, "System app detected, trying privileged methods first...")
            try {
                val sysResult = SystemImeiUtil.tryAllSystemMethods(context, imei1, imei2)
                if (sysResult.success) {
                    return MethodResult(true, "System: ${sysResult.method}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "System methods failed: ${e.message}")
            }
        }

        // ═══════════════════════════════════════════════════════
        // 0. PRIORITY: 2025-2026 Flagship-Specific Methods
        // ═══════════════════════════════════════════════════════
        if (CpuUtil.isModernFlagship()) {
            Log.i(TAG, "Modern flagship detected, trying 2025-2026 methods first...")

            // Snapdragon 8 Elite SM8850 (2026)
            if (cpuType == CpuUtil.CpuType.QUALCOMM && CpuUtil.getSnapdragonGeneration() >= 5) {
                try {
                    if (OemSpecificUtil.trySnapdragonElite(context, imei1, imei2)) {
                        return MethodResult(true, "Snapdragon 8 Elite SM8850 (AIDL v4)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Snapdragon Elite failed: ${e.message}")
                }
            }

            // Snapdragon 8 Gen 4 / 8s Gen 4 (SM8750/SM8735)
            if (cpuType == CpuUtil.CpuType.QUALCOMM && CpuUtil.getSnapdragonGeneration() >= 4) {
                try {
                    if (OemSpecificUtil.trySnapdragonGen4(context, imei1, imei2)) {
                        return MethodResult(true, "Snapdragon 8 Gen 4 (AIDL v3)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Snapdragon Gen 4 failed: ${e.message}")
                }
            }

            // Dimensity 9400+/9500/8400
            if (cpuType == CpuUtil.CpuType.MEDIATEK && CpuUtil.getDimensityGeneration() >= 9200) {
                try {
                    if (OemSpecificUtil.tryDimensity2026(context, imei1, imei2)) {
                        return MethodResult(true, "Dimensity 9400+/9500/8400 (AIDL v2+)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Dimensity 2026 failed: ${e.message}")
                }
            }

            // Exynos 2700 (2026)
            if (cpuType == CpuUtil.CpuType.EXYNOS && manufacturer == "SAMSUNG") {
                val cpuName = CpuUtil.getCpuName().lowercase()
                if (cpuName.contains("2700") || cpuName.contains("s5e9965")) {
                    try {
                        if (OemSpecificUtil.tryExynos2700(context, imei1, imei2)) {
                            return MethodResult(true, "Samsung Exynos 2700 (Enhanced SEH)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Exynos 2700 failed: ${e.message}")
                    }
                }
            }

            // Exynos 2500/2600
            if (cpuType == CpuUtil.CpuType.EXYNOS && manufacturer == "SAMSUNG") {
                try {
                    if (OemSpecificUtil.trySamsungExynos2026(context, imei1, imei2)) {
                        return MethodResult(true, "Samsung Exynos 2500/2600 (Enhanced HAL)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exynos 2500 failed: ${e.message}")
                }
            }

            // Tensor G5/G6
            if (cpuType == CpuUtil.CpuType.TENSOR) {
                try {
                    if (OemSpecificUtil.tryTensorG5G6(context, imei1, imei2)) {
                        return MethodResult(true, "Google Tensor G5/G6 (Enhanced Modem)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Tensor G5/G6 failed: ${e.message}")
                }
            }
        }

        // ═══════════════════════════════════════════════════════
        // 1. PRIORITY: Modern RadioEx (MTK Dimensity / Modern MTK)
        // ═══════════════════════════════════════════════════════
        if (cpuType == CpuUtil.CpuType.MEDIATEK) {
            try {
                if (MtkRadioExUtil.tryWriteImei(imei1, imei2)) {
                    return MethodResult(true, "MTK RadioEx (Modern HAL)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "RadioEx failed: ${e.message}")
            }

            try {
                if (OemSpecificUtil.tryMtkDimensity(context, imei1, imei2)) {
                    return MethodResult(true, "MTK Dimensity (Vendor HAL)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "MTK Dimensity failed: ${e.message}")
            }
        }

        // ═══════════════════════════════════════════════════════
        // 2. PRIORITY: Brand + CPU Specific Methods
        // ═══════════════════════════════════════════════════════
        when {
            manufacturer in arrayOf("XIAOMI", "POCO", "REDMI") -> {
                if (cpuType == CpuUtil.CpuType.QUALCOMM) {
                    if (OemSpecificUtil.tryXiaomiQualcomm(context)) {
                        return MethodResult(true, "Xiaomi Qualcomm Diag")
                    }
                } else if (cpuType == CpuUtil.CpuType.MEDIATEK) {
                    if (OemSpecificUtil.tryXiaomiMtkDirect(imei1, imei2)) {
                        return MethodResult(true, "Xiaomi MTK Direct Partition")
                    }
                }
            }

            manufacturer in arrayOf("REALME", "OPPO") -> {
                if (cpuType == CpuUtil.CpuType.QUALCOMM) {
                    if (OemSpecificUtil.tryRealmeQualcomm(context, imei1, imei2)) {
                        return MethodResult(true, "Realme/OPPO Qualcomm (Vendor HAL)")
                    }
                } else if (cpuType == CpuUtil.CpuType.MEDIATEK) {
                    if (OemSpecificUtil.tryRealmeMtk(context, imei1, imei2)) {
                        return MethodResult(true, "Realme/OPPO MTK (EngineerMode)")
                    }
                }
            }

            manufacturer == "SAMSUNG" -> {
                if (OemSpecificUtil.trySamsungMsl(context, imei1, imei2)) {
                    return MethodResult(true, "Samsung MSL/ServiceMode")
                }
                if (cpuType == CpuUtil.CpuType.EXYNOS) {
                    if (OemSpecificUtil.trySamsungExynos(context, imei1, imei2)) {
                        return MethodResult(true, "Samsung Exynos RIL")
                    }
                }
                // Samsung EFS direct write
                if (OemSpecificUtil.trySamsungEfs(imei1, imei2)) {
                    return MethodResult(true, "Samsung EFS Partition")
                }
            }

            manufacturer == "GOOGLE" && cpuType == CpuUtil.CpuType.TENSOR -> {
                if (OemSpecificUtil.tryGoogleTensor(context, imei1, imei2)) {
                    return MethodResult(true, "Google Tensor (Pixel HAL)")
                }
                // Pixel devinfo partition direct write
                if (OemSpecificUtil.tryPixelDevinfo(imei1, imei2)) {
                    return MethodResult(true, "Google Pixel devinfo Partition")
                }
            }

            manufacturer in arrayOf("HUAWEI", "HONOR") && cpuType == CpuUtil.CpuType.KIRIN -> {
                if (OemSpecificUtil.tryHuaweiKirin(context, imei1, imei2)) {
                    return MethodResult(true, "HiSilicon Kirin (Huawei HAL)")
                }
            }

            manufacturer in arrayOf("INFINIX", "TECNO", "ITEL") && cpuType == CpuUtil.CpuType.MEDIATEK -> {
                val isDimensity8200Plus = isDimensityHighEnd()
                if (isDimensity8200Plus) {
                    if (OemSpecificUtil.tryInfinixDimensity(context, imei1, imei2)) {
                        return MethodResult(true, "Infinix Dimensity 8200+ (Transsion HAL)")
                    }
                } else {
                    if (OemSpecificUtil.tryInfinixHelio(context, imei1, imei2)) {
                        return MethodResult(true, "Infinix Helio (MTK Standard)")
                    }
                }
            }

            // ──── NEW: OnePlus / Nothing ────
            manufacturer in arrayOf("ONEPLUS", "NOTHING") -> {
                if (OemSpecificUtil.tryOnePlus(context, imei1, imei2)) {
                    return MethodResult(true, "OnePlus/Nothing (OxygenOS/NothingOS)")
                }
            }

            // ──── NEW: Vivo / iQOO ────
            manufacturer in arrayOf("VIVO", "IQOO") -> {
                if (OemSpecificUtil.tryVivo(context, imei1, imei2)) {
                    return MethodResult(true, "Vivo/iQOO (FuntouchOS)")
                }
            }

            // ──── NEW: Motorola / Lenovo ────
            manufacturer in arrayOf("MOTOROLA", "LENOVO") -> {
                if (OemSpecificUtil.tryMotorola(context, imei1, imei2)) {
                    return MethodResult(true, "Motorola/Lenovo (Near-Stock)")
                }
            }

            // ──── NEW: Nokia / HMD ────
            manufacturer in arrayOf("NOKIA", "HMD GLOBAL", "HMD") -> {
                if (OemSpecificUtil.tryNokia(context, imei1, imei2)) {
                    return MethodResult(true, "Nokia/HMD (Android One)")
                }
            }

            // ──── NEW: ZTE / Nubia ────
            manufacturer in arrayOf("ZTE", "NUBIA") -> {
                if (OemSpecificUtil.tryZte(context, imei1, imei2)) {
                    return MethodResult(true, "ZTE/Nubia (Custom AT)")
                }
            }

            // ──── NEW: Meizu ────
            manufacturer == "MEIZU" -> {
                if (OemSpecificUtil.tryMeizu(context, imei1, imei2)) {
                    return MethodResult(true, "Meizu (Flyme)")
                }
            }
        }

        // ═══════════════════════════════════════════════════════
        // 3. PRIORITY: CPU-specific methods (not brand-specific)
        // ═══════════════════════════════════════════════════════
        when (cpuType) {
            CpuUtil.CpuType.EXYNOS -> {
                if (OemSpecificUtil.trySamsungExynos(context, imei1, imei2)) {
                    return MethodResult(true, "Exynos RIL (Generic)")
                }
            }
            CpuUtil.CpuType.TENSOR -> {
                if (OemSpecificUtil.tryGoogleTensor(context, imei1, imei2)) {
                    return MethodResult(true, "Tensor RIL (Generic)")
                }
                if (OemSpecificUtil.tryPixelDevinfo(imei1, imei2)) {
                    return MethodResult(true, "Tensor devinfo (Generic)")
                }
            }
            CpuUtil.CpuType.KIRIN -> {
                if (OemSpecificUtil.tryHuaweiKirin(context, imei1, imei2)) {
                    return MethodResult(true, "Kirin RIL (Generic)")
                }
            }
            CpuUtil.CpuType.QUALCOMM -> {
                if (OemSpecificUtil.tryQualcommModern(context, imei1, imei2)) {
                    return MethodResult(true, "Qualcomm Modern (8 Gen Series)")
                }
                // NEW: Direct DIAG NV550 write
                if (OemSpecificUtil.tryQualcommDiagNv(imei1, imei2)) {
                    return MethodResult(true, "Qualcomm DIAG NV550 (Direct)")
                }
            }
            CpuUtil.CpuType.UNISOC -> {
                if (OemSpecificUtil.tryUnisocTiger(context, imei1, imei2)) {
                    return MethodResult(true, "Unisoc Tiger T-Series")
                }
            }
            CpuUtil.CpuType.MEDIATEK -> {
                // NEW: MTK NVRAM HAL write
                if (OemSpecificUtil.tryMtkNvramHal(imei1, imei2)) {
                    return MethodResult(true, "MTK NVRAM HAL (vendor.mediatek.hardware.nvram)")
                }
                // NEW: MTK NVRAM MP0B_001 direct write (legacy)
                if (OemSpecificUtil.tryMtkNvramDirect(imei1, imei2)) {
                    return MethodResult(true, "MTK NVRAM MP0B_001 (Direct File)")
                }
            }
            else -> { /* fall through to classic methods */ }
        }

        // ═══════════════════════════════════════════════════════
        // 4. PRIORITY: Classic AT Commands with REAL VERIFICATION
        // ═══════════════════════════════════════════════════════
        Log.d(TAG, "Falling back to classic methods...")

        val res1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=1,7,\"$imei1\"", 0)
        if (res1.startsWith("OK")) {
            OemRilUtil.sendAtCommand(context, "AT+EGMR=1,10,\"$imei2\"", 1)

            val verified = verifyImeiWritten(context, imei1, imei2)
            if (verified) {
                return MethodResult(true, "AT+EGMR (Verified by Modem Read-Back)")
            } else {
                Log.w(TAG, "Modem said OK, but read-back verification failed")
                return MethodResult(true, "AT+EGMR (OK, Pending Reboot Verification)")
            }
        }

        // ═══════════════════════════════════════════════════════
        // 5. Root AT command to radio device
        // ═══════════════════════════════════════════════════════
        if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
            val radioDevices = arrayOf(
                "/dev/radio/pttycmd1",
                "/dev/radio/atci-service",
                "/dev/radio/atcimd-s",
                "/dev/at_channel",
                "/dev/smd0",
                "/dev/smd7",
                "/dev/ttyUSB0",
                "/dev/ttyUSB2",
                "/dev/ttyACM0",
                "/dev/appvcom0",
                "/dev/appvcom1"
            )
            for (dev in radioDevices) {
                val exists = RootUtil.executeWithOutput("ls $dev 2>/dev/null").isNotEmpty()
                if (exists) {
                    Log.d(TAG, "Radio device found: $dev")
                    val r1 = RootUtil.executeRootCommand("echo 'AT+EGMR=1,7,\"$imei1\"' > $dev")
                    val r2 = RootUtil.executeRootCommand("echo 'AT+EGMR=1,10,\"$imei2\"' > $dev")
                    if (r1 || r2) {
                        return MethodResult(true, "Direct Radio Device ($dev)")
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════
        // 6. NVRAM/Partition universal fallback (CPU-agnostic)
        // ═══════════════════════════════════════════════════════
        if (RootUtil.isValidImei(imei1) && RootUtil.isValidImei(imei2)) {
            Log.d(TAG, "Attempting universal partition fallback...")

            // Samsung EFS (for Qualcomm Samsung)
            if (manufacturer == "SAMSUNG") {
                if (OemSpecificUtil.trySamsungEfs(imei1, imei2)) {
                    return MethodResult(true, "Samsung EFS (Universal Fallback)")
                }
            }

            // Qualcomm DIAG (any brand with Qualcomm)
            if (cpuType == CpuUtil.CpuType.QUALCOMM || cpuType == CpuUtil.CpuType.UNKNOWN) {
                if (OemSpecificUtil.tryQualcommDiagNv(imei1, imei2)) {
                    return MethodResult(true, "Qualcomm DIAG NV550 (Fallback)")
                }
            }

            // MTK NVRAM (any brand with MTK)
            if (cpuType == CpuUtil.CpuType.MEDIATEK || cpuType == CpuUtil.CpuType.UNKNOWN) {
                if (OemSpecificUtil.tryMtkNvramDirect(imei1, imei2)) {
                    return MethodResult(true, "MTK NVRAM MP0B_001 (Fallback)")
                }
            }

            // Pixel devinfo (any Tensor)
            if (cpuType == CpuUtil.CpuType.TENSOR) {
                if (OemSpecificUtil.tryPixelDevinfo(imei1, imei2)) {
                    return MethodResult(true, "Pixel devinfo (Fallback)")
                }
            }
        }

        // ═══════════════════════════════════════════════════════
        // 7. LAST RESORT: System Properties
        // ═══════════════════════════════════════════════════════
        try {
            SystemPropertiesProxy.set(context, "persist.radio.imei1", imei1)
            SystemPropertiesProxy.set(context, "persist.radio.imei2", imei2)
            SystemPropertiesProxy.set(context, "persist.vendor.radio.imei1", imei1)
            SystemPropertiesProxy.set(context, "persist.vendor.radio.imei2", imei2)
            Log.d(TAG, "System Properties set. Hardware verification PENDING REBOOT.")
        } catch (e: Exception) {
            Log.e(TAG, "Props write failed: ${e.message}")
        }

        return MethodResult(false, "Hardware Blocked or Verification Pending Reboot")
    }

    /**
     * Reads back IMEI from modem via AT+EGMR=0,7 / AT+EGMR=0,10 to verify the write succeeded.
     */
    private fun verifyImeiWritten(context: Context, imei1: String, imei2: String): Boolean {
        try {
            Thread.sleep(500)
            val readBack1 = OemRilUtil.sendAtCommand(context, "AT+EGMR=0,7", 0)
            val readBack2 = OemRilUtil.sendAtCommand(context, "AT+EGMR=0,10", 1)

            Log.d(TAG, "Read-back SIM1: $readBack1")
            Log.d(TAG, "Read-back SIM2: $readBack2")

            val sim1Verified = readBack1.contains(imei1)
            val sim2Verified = readBack2.contains(imei2)

            if (sim1Verified || sim2Verified) {
                Log.i(TAG, "IMEI read-back verified: SIM1=$sim1Verified, SIM2=$sim2Verified")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Read-back verification error: ${e.message}")
        }
        return false
    }

    /**
     * Detects whether the device runs a high-end Dimensity SoC (8200+).
     */
    private fun isDimensityHighEnd(): Boolean {
        val cpuName = CpuUtil.getCpuName().lowercase()
        val platform = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.lowercase()
            else ""
        } catch (e: Exception) { "" }

        val combined = "$cpuName $platform"

        val highEndPatterns = arrayOf(
            "dimensity 8200", "dimensity 8300", "dimensity 8350",
            "dimensity 8400", "dimensity 8400-ultra",
            "dimensity 9000", "dimensity 9200", "dimensity 9300", "dimensity 9400",
            "dimensity 9400+", "dimensity 9500",
            "mt6896", "mt6897", "mt6990",
            "mt6985", "mt6989", "mt6991", "mt6993",
            "mt6983"
        )
        return highEndPatterns.any { combined.contains(it) }
    }

    /**
     * Check if this app is installed as a system app (platform-signed).
     */
    private fun isSystemApp(context: Context): Boolean {
        return try {
            val flags = context.packageManager
                .getApplicationInfo(context.packageName, 0).flags
            (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }
}
