package com.android.imeisettings.util

import android.content.Context
import android.os.IBinder
import android.util.Log
import java.util.ArrayList

object OemRilUtil {
    private const val TAG = "OemRilUtil"

    fun sendAtCommand(context: Context, command: String, slotIndex: Int): String {
        Log.d(TAG, "sendAtCommand: $command on slot $slotIndex")
        
        val formattedCmd = if (command.endsWith("\r")) command else command + "\r"
        val cmdBytes = (formattedCmd + "\u0000").toByteArray(Charsets.UTF_8)
        
        // 1. Попытка через ITelephonyEx (Метод из ImeiAndMeidSettings)
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = (getServiceMethod.invoke(null, "phoneEx") ?: getServiceMethod.invoke(null, "phone")) as? IBinder
            
            if (binder != null) {
                val stubClass = Class.forName("com.mediatek.internal.telephony.ITelephonyEx\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                val iTelephonyEx = asInterfaceMethod.invoke(null, binder)
                
                if (iTelephonyEx != null) {
                    val methods = iTelephonyEx.javaClass.methods
                    for (m in methods) {
                        if (m.name.contains("sendAtCommand") || m.name.contains("invokeOemRilRequest")) {
                            try {
                                m.isAccessible = true
                                when (m.parameterCount) {
                                    1 -> m.invoke(iTelephonyEx, formattedCmd)
                                    2 -> {
                                        if (m.parameterTypes[0] == Int::class.java) m.invoke(iTelephonyEx, slotIndex, formattedCmd)
                                        else m.invoke(iTelephonyEx, formattedCmd, slotIndex)
                                    }
                                    else -> continue
                                }
                                Log.d(TAG, "ITelephonyEx SUCCESS with method ${m.name}")
                                return "OK"
                            } catch (e: Exception) {
                                Log.w(TAG, "Method ${m.name} failed or blocked: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ITelephonyEx full attempt failed: ${e.message}")
        }

        // 2. Попытка через расширенные менеджеры MediaTek (Тот самый успешный цикл)
        val classNames = arrayOf(
            "com.mediatek.telephony.MtkTelephonyManagerEx",
            "com.mediatek.telephony.TelephonyManagerEx",
            "com.mediatek.telephony.MtkTelephonyManager"
        )

        for (className in classNames) {
            try {
                val clazz = Class.forName(className)
                val instance = clazz.getMethod("getDefault").invoke(null) ?: continue
                val methods = clazz.methods
                
                for (method in methods) {
                    if ((method.name.contains("invokeOemRilRequestRaw") || method.name.contains("sendAtCommand"))
                        && method.parameterCount >= 1) {
                        
                        try {
                            method.isAccessible = true
                            val types = method.parameterTypes
                            
                            Log.d(TAG, "Testing method: $className.${method.name}(${types.joinToString { it.simpleName }})")
                            
                            when (method.parameterCount) {
                                1 -> {
                                    if (types[0] == ByteArray::class.java) method.invoke(instance, cmdBytes)
                                    else if (types[0] == String::class.java) method.invoke(instance, formattedCmd)
                                }
                                2 -> {
                                    val arg1 = if (types[0] == Int::class.java) 1004 else if (types[0] == ByteArray::class.java) cmdBytes else formattedCmd
                                    val arg2 = if (types[1] == Int::class.java) slotIndex else if (types[1] == ByteArray::class.java) cmdBytes else formattedCmd
                                    method.invoke(instance, arg1, arg2)
                                }
                                3 -> {
                                    val args = arrayOfNulls<Any>(3)
                                    for (i in 0..2) {
                                        args[i] = when (types[i]) {
                                            Int::class.java -> if (i == 0) slotIndex else 1004
                                            ByteArray::class.java -> cmdBytes
                                            String::class.java -> formattedCmd
                                            else -> null
                                        }
                                    }
                                    method.invoke(instance, *args)
                                }
                            }
                            Log.d(TAG, "SUCCESS with $className.${method.name}")
                            return "OK"
                        } catch (e: Throwable) {
                            Log.w(TAG, "Method execution failed: $className.${method.name} -> ${e.message}")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Try $className failed: ${e.message}")
            }
        }

        // Root Fallback — write AT command to modem port within root session
        return try {
            val sanitizedCmd = formattedCmd.replace("'", "'\\''")
            val ports = arrayOf("/dev/radio/pttycmd1", "/dev/ttyC0", "/dev/radio/atci")
            var rootSucceeded = false
            for (port in ports) {
                if (RootUtil.executeWithOutput("ls $port").isEmpty()) continue
                if (RootUtil.executeRootCommand("printf '%s' '$sanitizedCmd' > $port")) {
                    rootSucceeded = true
                    break
                }
            }
            if (rootSucceeded) "OK (Port)" else "Error"
        } catch (e: Exception) {
            Log.e(TAG, "Root fallback AT command failed: ${e.message}")
            "Error"
        }
    }
}
