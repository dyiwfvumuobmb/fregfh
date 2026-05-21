package com.android.imeisettings.util

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log

/**
 * Ультимативный обход ограничений Android на использование Hidden API.
 * Исправленная версия с поддержкой Meta-Reflection.
 */
object HiddenApiBypass {
    private const val TAG = "CONSUL_BYPASS"

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    fun bypassRestrictions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        
        return try {
            // Пытаемся через "Bootstrap" метод с правильной упаковкой аргументов
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val runtime = getRuntimeMethod.invoke(null)
            
            // На Android 12+ прямое получение setHiddenApiExemptions через getDeclaredMethod может быть заблокировано
            // Используем Meta-Reflection: получаем метод через рефлексию над самим классом Class
            val getDeclaredMethodOfClass = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val setExemptionsMethod = getDeclaredMethodOfClass.invoke(vmRuntimeClass, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)) as java.lang.reflect.Method
            
            // Флаг "L" отключает все проверки для нашего процесса. 
            // Важно: передаем как Object[] { String[] { "L" } }
            setExemptionsMethod.invoke(runtime, arrayOf(arrayOf("L")))
            
            Log.i(TAG, "СИСТЕМА: Ограничения Hidden API успешно сняты (Meta-Reflection).")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Meta-Reflection failed: ${e.message}. Trying direct legacy path...")
            try {
                // Старый классический путь (на случай если Meta заблокирована, а база - нет)
                val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
                val runtime = vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null)
                val setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                setExemptions.invoke(runtime, arrayOf("L") as Any)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Критическая ошибка обхода: ${e2.message}")
                try {
                    // Резервный Root-путь через shell
                    RootUtil.executeRootCommand("settings put global hidden_api_policy 1")
                    true
                } catch (e3: Exception) { false }
            }
        }
    }
}
