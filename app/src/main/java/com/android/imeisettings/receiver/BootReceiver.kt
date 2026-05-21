package com.android.imeisettings.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.service.ImeiRotationWorker
import com.android.imeisettings.service.NetworkSecurityService
import com.android.imeisettings.service.PduInterceptorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.SIM_STATE_CHANGED" || action == "android.intent.action.SERVICE_STATE") {
            val pendingResult = goAsync()

            // 1. Start security services
            startServicesWithFallback(context)

            // 2. Restore scheduled IMEI rotation if enabled
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope.launch {
                    try {
                        val settings = SettingsDataStore.getInstance(context)
                        val autoRotate = settings.autoRotateEnabled.first()
                        if (autoRotate) {
                            val interval = settings.autoRotateIntervalHours.first()
                            ImeiRotationWorker.schedule(context, interval)
                            Log.d("BootReceiver", "Restored IMEI rotation schedule: every ${interval}h")
                        }
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Failed to restore rotation schedule: ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            } else {
                pendingResult.finish()
            }
        }
    }

    private fun startServicesWithFallback(context: Context) {
        val securityIntent = Intent(context, NetworkSecurityService::class.java)
        val pduIntent = Intent(context, PduInterceptorService::class.java)

        try {
            context.startForegroundService(securityIntent)
            context.startForegroundService(pduIntent)
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 14+: cannot start FGS from background in some cases
            Log.w("BootReceiver", "FGS start not allowed from background, deferring: ${e.message}")
        } catch (e: Exception) {
            try {
                context.startService(securityIntent)
                context.startService(pduIntent)
            } catch (e2: Exception) {
                Log.e("BootReceiver", "Failed to start security services: ${e2.message}")
            }
        }
    }
}
