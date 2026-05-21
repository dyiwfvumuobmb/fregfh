package com.android.imeisettings.util

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object DeviceIdentifierUtil {
    
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getImei(context: Context, slotIndex: Int): String {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.getImei(slotIndex) ?: getImeiViaRoot(slotIndex)
        } catch (e: Exception) {
            getImeiViaRoot(slotIndex)
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun getImsi(context: Context, slotIndex: Int): String {
        return try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            val infoList = subscriptionManager.activeSubscriptionInfoList
            if (infoList != null) {
                for (info in infoList) {
                    if (info.simSlotIndex == slotIndex) {
                        val tm = telephonyManager.createForSubscriptionId(info.subscriptionId)
                        return tm.subscriberId ?: "NO SIM"
                    }
                }
            }
            "NO SIM"
        } catch (e: Exception) {
            "N/A"
        }
    }

    private fun getImeiViaRoot(slotIndex: Int): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "service call iphonesubinfo ${if (slotIndex == 0) "1" else "2"}"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line)
            }
            val completed = process.waitFor(30, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return "N/A"
            }
            
            val raw = output.toString()
            if (raw.isNotEmpty()) {
                parseServiceCallOutput(raw)
            } else {
                "N/A"
            }
        } catch (e: Exception) {
            "N/A"
        }
    }

    private fun parseServiceCallOutput(output: String): String {
        val result = StringBuilder()
        val regex = Regex("'([0-9.]+)'")
        val matches = regex.findAll(output)
        for (match in matches) {
            result.append(match.groupValues[1].replace(".", ""))
        }
        val cleaned = result.toString()
        return if (cleaned.length >= 15) cleaned.substring(0, 15) else "N/A"
    }
}
