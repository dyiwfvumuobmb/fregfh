package com.android.imeisettings.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object KillSwitchController {
    private const val TAG = "KillSwitchController"

    private val _isRadioDisabled = MutableStateFlow(false)
    val isRadioDisabled: StateFlow<Boolean> = _isRadioDisabled
    private val lock = Any()

    /**
     * Экстренное отключение радиомодуля.
     * Использует системные API (как системное приложение) + RootFallback.
     */
    fun triggerKillSwitch(context: Context): Boolean {
        synchronized(lock) {
            if (_isRadioDisabled.value) return true
            Log.w(TAG, "!!! TRIGGERING HARDWARE KILL SWITCH !!!")

            // 1. Агрессивный метод: Прямая AT-команда модему (Power Off Radio)
            try {
                OemRilUtil.sendAtCommand(context, "AT+CFUN=0", 0)
                OemRilUtil.sendAtCommand(context, "AT+CFUN=0", 1)
            } catch (e: Exception) {
                Log.e(TAG, "AT KillSwitch failed: ${e.message}")
            }

            // 2. Системный API (System App): setRadioPower(false)
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val method = tm.javaClass.getMethod("setRadioPower", Boolean::class.javaPrimitiveType)
                method.invoke(tm, false)
            } catch (e: Exception) {
                Log.w(TAG, "setRadioPower via reflection failed, using shell fallback", e)
                RootUtil.executeRootCommand("cmd phone radio power off")
            }
        
            // 3. Режим полета: Напрямую через ContentResolver как системное приложение
            try {
                Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", true)
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Airplane mode toggle failed", e)
                RootUtil.executeRootCommand("settings put global airplane_mode_on 1")
                RootUtil.executeRootCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
            }
        
            // 4. Отключение передачи данных
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val dataMethod = tm.javaClass.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
                dataMethod.invoke(tm, false)
            } catch (e: Exception) {
                Log.e(TAG, "setDataEnabled failed: ${e.message}")
                RootUtil.executeRootCommand("svc data disable")
            }

            _isRadioDisabled.value = true
            return true
        }
    }

    /**
     * Восстановление связи.
     */
    fun restoreRadio(context: Context): Boolean {
        synchronized(lock) {
            if (!_isRadioDisabled.value) return true
            Log.i(TAG, "Restoring radio connectivity...")

            try {
                OemRilUtil.sendAtCommand(context, "AT+CFUN=1", 0)
                OemRilUtil.sendAtCommand(context, "AT+CFUN=1", 1)
            } catch (e: Exception) {
                Log.e(TAG, "AT restore failed: ${e.message}")
            }

            // Восстановление через System API
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val method = tm.javaClass.getMethod("setRadioPower", Boolean::class.javaPrimitiveType)
                method.invoke(tm, true)
            } catch (e: Exception) {
                Log.e(TAG, "setRadioPower restore failed: ${e.message}")
                RootUtil.executeRootCommand("cmd phone radio power on")
            }

            try {
                Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", false)
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Airplane mode restore failed: ${e.message}")
                RootUtil.executeRootCommand("settings put global airplane_mode_on 0")
                RootUtil.executeRootCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
            }

            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val dataMethod = tm.javaClass.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
                dataMethod.invoke(tm, true)
            } catch (e: Exception) {
                Log.e(TAG, "setDataEnabled restore failed: ${e.message}")
                RootUtil.executeRootCommand("svc data enable")
            }

            _isRadioDisabled.value = false
            return true
        }
    }
}
