package com.android.imeisettings.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.android.imeisettings.util.ImeiChangerUtil
import com.android.imeisettings.util.RootUtil
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class ImeiWriteService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val imei1 = intent.getStringExtra("imei1") ?: ""
            val imei2 = intent.getStringExtra("imei2") ?: ""

            if (!RootUtil.isValidImei(imei1) || !RootUtil.isValidImei(imei2)) {
                Log.e("ImeiWriteService", "Invalid IMEI format rejected: imei1=$imei1, imei2=$imei2")
                writeResult("Error")
                stopSelf()
                return START_NOT_STICKY
            }

            serviceScope.launch {
                val result = ImeiChangerUtil.writeImei(this@ImeiWriteService, imei1, imei2)

                try {
                    val file = File(cacheDir, "imei_write_result.txt")
                    FileOutputStream(file).use { it.write(result.status.toByteArray()) }
                } catch (e: Exception) {
                    Log.e("ImeiWriteService", "Failed to write result: ${e.message}")
                }
                stopSelf()
            }
        } else {
            stopSelf()
        }
        
        return START_NOT_STICKY
    }

    private fun writeResult(status: String) {
        try {
            val file = File(cacheDir, "imei_write_result.txt")
            FileOutputStream(file).use { it.write(status.toByteArray()) }
        } catch (e: Exception) {
            Log.e("ImeiWriteService", "Failed to write result: ${e.message}")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
