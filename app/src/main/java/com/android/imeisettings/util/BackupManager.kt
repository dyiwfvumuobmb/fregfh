package com.android.imeisettings.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.android.imeisettings.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object BackupManager {
    suspend fun backupLogsToText(context: Context): File? {
        Log.d("CONSUL_SETTINGS", "Starting backupLogsToText")
        return try {
            val db = AppDatabase.getDatabase(context)
            val logs = db.securityLogDao().getAllLogs().first()
            Log.d("CONSUL_SETTINGS", "Fetched ${logs.size} logs from database")
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "CONSUL_LOGS_$timestamp.txt"

            val content = buildString {
                append("CONSUL IMEI SECURITY LOGS - EXPORTED ON ${Date()}\n")
                append("--------------------------------------------------\n\n")
                logs.forEach { log ->
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                    append("[$date] [${log.type}] SIM ${log.simSlot ?: "N/A"}: ${log.message}\n")
                    if (log.pdu != null) append("PDU: ${log.pdu}\n")
                    append("\n")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: use MediaStore to save to shared Documents
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/CONSUL")
                }
                val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    Log.d("CONSUL_SETTINGS", "Saved via MediaStore: $uri")
                    // Return a placeholder file to signal success
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CONSUL/$fileName")
                } else {
                    Log.e("CONSUL_SETTINGS", "MediaStore insert returned null")
                    null
                }
            } else {
                // Legacy: write directly to external storage
                val docDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                val file = File(docDir, fileName)
                file.writeText(content)
                Log.d("CONSUL_SETTINGS", "File written to: ${file.absolutePath}")
                file
            }
        } catch (e: Exception) {
            Log.e("CONSUL_SETTINGS", "Backup Error: ${e.message}", e)
            null
        }
    }
}
