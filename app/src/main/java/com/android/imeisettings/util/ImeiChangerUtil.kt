package com.android.imeisettings.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.SecurityLog
import com.android.imeisettings.data.local.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class UpdateResult(
    val status: String,
    val newWifiMac: String? = null,
    val newBtMac: String? = null,
    val newAndroidId: String? = null
)

object ImeiChangerUtil {
    private const val TAG = "ImeiChangerUtil"
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    suspend fun writeImei(context: Context, imei1: String, imei2: String): UpdateResult {
        val db = AppDatabase.getDatabase(context)
        val cpuType = CpuUtil.getCpuType()
        
        fun log(msg: String, type: String = "INFO") {
            Log.d(TAG, "[$type] $msg")
            scope.launch {
                db.securityLogDao().insertLog(SecurityLog(id = 0, timestamp = System.currentTimeMillis(), type = type, message = "Update: $msg"))
            }
        }

        return try {
            if (!RootUtil.isValidImei(imei1) || !ImeiGenerator.isValidImei(imei1)) {
                log("IMEI 1 validation failed: $imei1", "WARNING")
                return UpdateResult("Error", newWifiMac = "Invalid IMEI 1: Luhn check failed")
            }
            if (!RootUtil.isValidImei(imei2) || !ImeiGenerator.isValidImei(imei2)) {
                log("IMEI 2 validation failed: $imei2", "WARNING")
                return UpdateResult("Error", newWifiMac = "Invalid IMEI 2: Luhn check failed")
            }

            // Prevent Repeats: check if IMEI was already used
            val settings = SettingsDataStore.getInstance(context)
            val preventRepeats = settings.preventRepeats.first()
            if (preventRepeats) {
                val history = db.imeiHistoryDao().getAllHistory().first()
                val usedImeis = history.flatMap { listOf(it.sim1NewImei, it.sim2NewImei) }.toSet()
                if (imei1 in usedImeis || imei2 in usedImeis) {
                    log("Prevent Repeats: IMEI already used, rejecting", "WARNING")
                    return UpdateResult("Error", newWifiMac = "IMEI already used (Prevent Repeats enabled)")
                }
            }
            
            log("Global rotation started for ${CpuUtil.getCpuName()}")

            val result = AutoMethodSelector.executeBestMethod(context, imei1, imei2)
            log("Method selected: ${result.methodName}")

            if (result.success) {
                log("SUCCESS: Written via ${result.methodName}")
            } else {
                log("AutoMethodSelector exhausted all methods", "WARNING")
            }

            if (result.success) {
                // Write spoofed IMEI to SharedPreferences for Xposed hooks
                updateXposedSpoofPrefs(context, imei1, imei2)

                // Privacy Purge: Clean logs on success if enabled
                // Wait for purge to complete before any reboot
                val purgeSettings = SettingsDataStore.getInstance(context)
                if (purgeSettings.cleanLogsOnImeiChange.first()) {
                    log("Privacy Purge: Clearing all security logs...", "INFO")
                    db.securityLogDao().deleteAllLogs()
                    db.cellFingerprintDao().deleteAll()
                }
                finalizeCommit(context)

                // Auto Reboot after IMEI change if enabled (runs after purge completes)
                val autoReboot = settings.autoRebootAfterImei.first()
                if (autoReboot) {
                    log("Auto Reboot: Scheduling device reboot...", "INFO")
                    scope.launch {
                        delay(2000)
                        triggerReboot(context)
                    }
                }
                UpdateResult("OK")
            } else {
                UpdateResult("Error", newWifiMac = "All injection methods returned failure status")
            }
        } catch (e: Exception) {
            log("Critical Error: ${e.message}", "ALERT")
            UpdateResult("Error", newWifiMac = e.message)
        }
    }

    fun preCheck(context: Context): PreCheckResult {
        val cpu = CpuUtil.getCpuName()
        val cpuType = CpuUtil.getCpuType()
        
        // 1. Проверка UID (Android System User ID)
        val myUid = android.os.Process.myUid()
        val isSystemUid = (myUid == 1000 || myUid == 1001 || myUid == 0)

        // 2. Тест на запись в защищенную область (Dry Run)
        val canWriteProps = try {
            val testProp = "persist.sys.consul.check"
            SystemPropertiesProxy.set(context, testProp, "1")
            val check = SystemPropertiesProxy.get(context, testProp, "0")
            check == "1"
        } catch (e: Exception) { false }

        // 3. Глубокая проверка API по типу CPU
        var apiDetails = ""
        val apiAccessible = when (cpuType) {
            CpuUtil.CpuType.MEDIATEK -> {
                try {
                    val clazz = Class.forName("com.mediatek.nvram.NvRAMUtils")
                    clazz.getMethod("writeNV", Int::class.java, ByteArray::class.java)
                    apiDetails = "MTK NVRAM: OK"
                    true
                } catch (e: Exception) { 
                    apiDetails = "MTK NVRAM: BLOCKED"
                    false 
                }
            }
            CpuUtil.CpuType.QUALCOMM, CpuUtil.CpuType.UNISOC, CpuUtil.CpuType.EXYNOS,
            CpuUtil.CpuType.TENSOR, CpuUtil.CpuType.KIRIN -> {
                val hasRil = try {
                    val smClass = Class.forName("android.os.ServiceManager")
                    val getService = smClass.getMethod("getService", String::class.java)
                    val binder = getService.invoke(null, "phoneEx") ?: getService.invoke(null, "phone")
                    binder != null
                } catch (e: Exception) { false }
                apiDetails = if (hasRil) "RIL Bus: Connected ($cpuType)" else "RIL Bus: Denied ($cpuType)"
                hasRil
            }
            else -> {
                apiDetails = "AOSP Bridge: OK"
                canWriteProps
            }
        }

        val isReady = isSystemUid && canWriteProps && apiAccessible
        
        val statusMsg = when {
            isReady -> "READY: All systems operational"
            !isSystemUid -> "FATAL: Not a System App"
            !canWriteProps -> "ERROR: Signature Denied"
            else -> "WARNING: API Restricted"
        }

        return PreCheckResult(
            isReady = isReady,
            cpuInfo = cpu,
            statusMessage = statusMsg,
            details = "UID: $myUid, Props: $canWriteProps, $apiDetails"
        )
    }

    data class PreCheckResult(
        val isReady: Boolean,
        val cpuInfo: String,
        val statusMessage: String,
        val details: String
    )

    private fun triggerReboot(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.reboot("IMEI change applied")
        } catch (e: Exception) {
            Log.w(TAG, "PowerManager reboot failed, trying shell fallback", e)
            try {
                RootUtil.executeRootCommand("reboot")
            } catch (e2: Exception) {
                Log.e(TAG, "All reboot methods failed: ${e2.message}")
            }
        }
    }

    @SuppressWarnings("deprecation")
    private fun updateXposedSpoofPrefs(context: Context, imei1: String, imei2: String) {
        try {
            @Suppress("DEPRECATION")
            val prefs = context.getSharedPreferences("consul_imei_xposed", Context.MODE_WORLD_READABLE)
            prefs.edit()
                .putBoolean("spoof_enabled", true)
                .putString("spoofed_imei_1", imei1)
                .putString("spoofed_imei_2", imei2)
                .putString("spoofed_meid", imei1)
                .apply()

            // Ensure file permissions allow Xposed process to read
            try {
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                prefsDir.setExecutable(true, false)
                prefsDir.setReadable(true, false)
                val prefsFile = File(prefsDir, "consul_imei_xposed.xml")
                if (prefsFile.exists()) {
                    prefsFile.setReadable(true, false)
                }
                // Also set via root for SELinux-enforced devices
                RootUtil.executeRootCommand("chmod 755 ${prefsDir.absolutePath}")
                RootUtil.executeRootCommand("chmod 644 ${prefsFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set world-readable permissions: ${e.message}")
            }

            Log.d(TAG, "Xposed spoof prefs updated: SIM1=$imei1, SIM2=$imei2")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write Xposed spoof prefs: ${e.message}")
        }
    }

    private fun finalizeCommit(context: Context) {
        scope.launch {
            val db = AppDatabase.getDatabase(context)
            db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "INFO", "Modem commit sequence initiated..."))
        }
        arrayOf(0, 1).forEach { slot ->
            OemRilUtil.sendAtCommand(context, "AT+EPON", slot)
            OemRilUtil.sendAtCommand(context, "AT+EAIC=2", slot)
            OemRilUtil.sendAtCommand(context, "AT+CFUN=1,1", slot)
        }
    }

    private fun encodeImeiFactory(imei: String): ByteArray {
        val result = ByteArray(9) // Стандартный размер для MTK NVRAM
        try {
            // Первый байт - длина (обычно 0x08)
            result[0] = 0x08
            
            // Кодирование в BCD (Binary Coded Decimal) со смещением
            // Формат: [Length] [Odd/Even + Digit1] [Digit3 + Digit2] ...
            val digits = imei.map { it - '0' }
            
            // Байт 1: 0x0A (Odd parity indicator) + Первая цифра
            result[1] = ((digits[0] shl 4) or 0x0A).toByte()
            
            // Остальные пары цифр
            for (i in 0..6) {
                val d2 = digits[i * 2 + 1]
                val d3 = digits[i * 2 + 2]
                result[i + 2] = ((d3 shl 4) or d2).toByte()
            }
        } catch (e: Exception) {
            Log.e(TAG, "BCD Encoding error: ${e.message}")
        }
        return result
    }
}
