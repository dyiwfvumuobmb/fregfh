package com.android.imeisettings.util

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootUtil {
    private const val TAG = "RootUtil"
    private const val PROCESS_TIMEOUT_SECONDS = 30L

    private val SHELL_SAFE_REGEX = Regex("^[a-zA-Z0-9_./:=\\-+,\"' @\\\\|><&;%#*{}()\\[\\]\\n\\t^~]+$")
    private val SHELL_INJECTION_PATTERNS = Regex("\\$\\(|`|\\$\\{|\\\\x[0-9a-fA-F]{2}")

    private fun isSafeForShell(input: String): Boolean {
        if (SHELL_INJECTION_PATTERNS.containsMatchIn(input)) {
            Log.e(TAG, "Command injection attempt detected")
            return false
        }
        return SHELL_SAFE_REGEX.matches(input)
    }

    fun isValidImei(imei: String): Boolean {
        return imei.matches(Regex("^[0-9]{15}$"))
    }

    fun isDeviceRooted(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun executeRootCommand(command: String): Boolean {
        if (!isSafeForShell(command)) {
            Log.e(TAG, "Rejected unsafe shell command: contains forbidden characters")
            return false
        }
        var os: DataOutputStream? = null
        return try {
            val process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()
            val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                Log.e(TAG, "Root command timed out after ${PROCESS_TIMEOUT_SECONDS}s")
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed: ${e.message}")
            false
        } finally {
            try {
                os?.close()
            } catch (e: IOException) {}
        }
    }

    fun executeWithOutput(command: String): String {
        if (!isSafeForShell(command)) {
            Log.e(TAG, "Rejected unsafe shell command: contains forbidden characters")
            return ""
        }
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                Log.e(TAG, "Root command with output timed out after ${PROCESS_TIMEOUT_SECONDS}s")
                process.destroyForcibly()
            }
            os.close()
            output.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Root command with output failed: ${e.message}")
            ""
        }
    }

    /**
     * Универсальный метод перезагрузки для системного приложения.
     */
    fun rebootDevice(context: Context) {
        Log.d(TAG, "rebootDevice: Starting reboot sequence...")
        
        // 1. Пытаемся через PowerManager (лучший вариант для UID 1001)
        try {
            Log.d(TAG, "rebootDevice: Attempting PowerManager.reboot")
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.reboot(null)
            Log.d(TAG, "rebootDevice: PowerManager.reboot called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "rebootDevice: PowerManager failed: ${e.message}")
        }

        // 2. Пытаемся через прямой шелл (для системных процессов часто открыто)
        try {
            Log.d(TAG, "rebootDevice: Attempting Runtime.exec(reboot)")
            Runtime.getRuntime().exec("reboot")
        } catch (e: Exception) {
            Log.e(TAG, "rebootDevice: Runtime.exec failed: ${e.message}")
        }

        // 3. Пытаемся через системную проперти
        try {
            Log.d(TAG, "rebootDevice: Attempting setprop sys.powerctl reboot")
            Runtime.getRuntime().exec("setprop sys.powerctl reboot")
        } catch (e: Exception) {
            Log.e(TAG, "rebootDevice: setprop failed: ${e.message}")
        }

        // 4. Крайний случай - через Root (если Magisk одобрит su для системного процесса)
        Log.d(TAG, "rebootDevice: Falling back to su - svc power reboot")
        executeRootCommand("svc power reboot || reboot")
    }

    /**
     * Execute root command that writes binary data via hex encoding.
     * Uses a separate validation path since hex data contains \x sequences.
     */
    fun writeHexToFile(hexData: String, filePath: String): Boolean {
        if (!isSafeForShell(filePath)) {
            Log.e(TAG, "Rejected unsafe file path")
            return false
        }
        // Validate hex data format: should only contain \x followed by hex digits
        if (!hexData.matches(Regex("^(\\\\x[0-9a-fA-F]{2})+$"))) {
            Log.e(TAG, "Rejected invalid hex data format")
            return false
        }
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("printf '${hexData}' > $filePath\n")
            os.writeBytes("exit\n")
            os.flush()
            val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeHexToFile failed: ${e.message}")
            false
        }
    }

    fun fileExists(path: String): Boolean {
        return executeWithOutput("ls $path 2>/dev/null").isNotEmpty()
    }

    fun backupFile(path: String): Boolean {
        return executeRootCommand("cp $path ${path}.consul_bak")
    }

    /**
     * Спеціальний метод для негайного перезавантаження ядра на Android 15.
     */
    fun forceReboot() {
        try {
            executeRootCommand("setprop sys.powerctl reboot")
            executeRootCommand("svc power reboot")
            executeRootCommand("reboot")
        } catch (e: Exception) {
            Log.e(TAG, "forceReboot failed: ${e.message}")
        }
    }
}
