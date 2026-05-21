package com.android.imeisettings.util

import androidx.annotation.Keep

@Keep
object XposedCheck {
    /**
     * Этот метод будет перехвачен Xposed модулем.
     * Если модуль активен, он вернет true.
     */
    @Keep
    @JvmStatic
    fun isModuleActive(): Boolean {
        return false
    }
}
