package com.android.imeisettings.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

object SystemPropertiesProxy {
    private const val TAG = "SystemPropertiesProxy"

    @SuppressLint("PrivateApi")
    fun get(context: Context, key: String, def: String = ""): String {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, def) as String
        } catch (e: Exception) {
            Log.e(TAG, "Error getting system property $key", e)
            def
        }
    }

    @SuppressLint("PrivateApi")
    fun set(context: Context, key: String, value: String) {
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, key, value)
            Log.d(TAG, "Successfully set system property $key to $value")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting system property $key", e)
            // If reflection fails and we have root, try via shell with sanitized args
            val safeKey = Regex("^[a-zA-Z0-9._-]+$")
            val safeValue = Regex("^[a-zA-Z0-9._:/-]+$")
            if (safeKey.matches(key) && safeValue.matches(value)) {
                try {
                    RootUtil.executeRootCommand("setprop $key $value")
                } catch (rootEx: Exception) {
                    Log.e(TAG, "Root setprop failed too", rootEx)
                }
            } else {
                Log.e(TAG, "Rejected unsafe setprop key/value: contains forbidden characters")
            }
        }
    }
}
