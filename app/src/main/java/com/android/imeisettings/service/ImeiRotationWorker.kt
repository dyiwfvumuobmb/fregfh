package com.android.imeisettings.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.android.imeisettings.MainActivity
import com.android.imeisettings.R
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.ImeiHistory
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.util.DeviceIdentifierUtil
import com.android.imeisettings.util.ImeiChangerUtil
import com.android.imeisettings.util.ImeiGenerator
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class ImeiRotationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ImeiRotationWorker"
        private const val WORK_NAME = "imei_auto_rotation"
        private const val CHANNEL_ID = "ImeiRotationChannel"
        private const val NOTIFICATION_ID_SUCCESS = 5001
        private const val NOTIFICATION_ID_FAILURE = 5002

        fun schedule(context: Context, intervalHours: Int) {
            val wm = WorkManager.getInstance(context)

            val periodicRequest = PeriodicWorkRequestBuilder<ImeiRotationWorker>(
                intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )

            val initialRequest = OneTimeWorkRequestBuilder<ImeiRotationWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build()
            wm.enqueue(initialRequest)

            Log.d(TAG, "Scheduled IMEI rotation every $intervalHours hours (initial in 1 min)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled IMEI rotation")
        }

        fun showRotationNotification(context: Context, success: Boolean, trigger: String, newImei1: String, newImei2: String) {
            createChannelIfNeeded(context)

            val lang = try {
                val settings = SettingsDataStore.getInstance(context)
                kotlinx.coroutines.runBlocking { settings.selectedLanguage.first() }
            } catch (_: Exception) { "en" }

            val title: String
            val body: String

            val shortImei1 = if (newImei1.length >= 6) "...${newImei1.takeLast(6)}" else newImei1
            val shortImei2 = if (newImei2.length >= 6) "...${newImei2.takeLast(6)}" else newImei2

            if (success) {
                title = when (lang) {
                    "ru" -> "IMEI изменён ($trigger)"
                    "uk" -> "IMEI змінено ($trigger)"
                    "de" -> "IMEI geändert ($trigger)"
                    "pl" -> "IMEI zmieniony ($trigger)"
                    "es" -> "IMEI cambiado ($trigger)"
                    "lt" -> "IMEI pakeistas ($trigger)"
                    "lv" -> "IMEI nomainīts ($trigger)"
                    else -> "IMEI Changed ($trigger)"
                }
                body = "SIM1: $shortImei1 | SIM2: $shortImei2"
            } else {
                title = when (lang) {
                    "ru" -> "Ошибка ротации IMEI ($trigger)"
                    "uk" -> "Помилка ротації IMEI ($trigger)"
                    "de" -> "IMEI-Rotation fehlgeschlagen ($trigger)"
                    "pl" -> "Błąd rotacji IMEI ($trigger)"
                    "es" -> "Error de rotación IMEI ($trigger)"
                    "lt" -> "IMEI rotacijos klaida ($trigger)"
                    "lv" -> "IMEI rotācijas kļūda ($trigger)"
                    else -> "IMEI Rotation Failed ($trigger)"
                }
                body = when (lang) {
                    "ru" -> "Не удалось записать новый IMEI. Проверьте root-доступ."
                    "uk" -> "Не вдалося записати новий IMEI. Перевірте root-доступ."
                    "de" -> "IMEI konnte nicht geschrieben werden. Root-Zugriff prüfen."
                    "pl" -> "Nie udało się zapisać nowego IMEI. Sprawdź dostęp root."
                    "es" -> "No se pudo escribir el nuevo IMEI. Verifique el acceso root."
                    "lt" -> "Nepavyko įrašyti naujo IMEI. Patikrinkite root prieigą."
                    "lv" -> "Neizdevās ierakstīt jauno IMEI. Pārbaudiet root piekļuvi."
                    else -> "Failed to write new IMEI. Check root access."
                }
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_consul)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(if (success) NOTIFICATION_ID_SUCCESS else NOTIFICATION_ID_FAILURE, notification)
        }

        private fun createChannelIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "IMEI Rotation Results",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Notifications about auto-rotation and network-change rotation results"
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val settings = SettingsDataStore.getInstance(applicationContext)
            val autoRotateEnabled = settings.autoRotateEnabled.first()
            if (!autoRotateEnabled) {
                Log.d(TAG, "Auto-rotation disabled, skipping")
                return Result.success()
            }

            val oldImei1 = DeviceIdentifierUtil.getImei(applicationContext, 0)
            val oldImei2 = DeviceIdentifierUtil.getImei(applicationContext, 1)

            var newImei1 = ImeiGenerator.generateImei()
            var newImei2 = ImeiGenerator.generateImei()

            val preventRepeats = settings.preventRepeats.first()
            if (preventRepeats) {
                val db = AppDatabase.getDatabase(applicationContext)
                val history = db.imeiHistoryDao().getAllHistory().first()
                val usedImeis = history.flatMap { listOf(it.sim1NewImei, it.sim2NewImei) }.toSet()
                var attempts = 0
                while ((newImei1 == oldImei1 || newImei1 in usedImeis) && attempts < 20) {
                    newImei1 = ImeiGenerator.generateImei()
                    attempts++
                }
                attempts = 0
                while ((newImei2 == oldImei2 || newImei2 in usedImeis) && attempts < 20) {
                    newImei2 = ImeiGenerator.generateImei()
                    attempts++
                }
            }

            Log.d(TAG, "Auto-rotating IMEI: SIM1=$oldImei1→$newImei1, SIM2=$oldImei2→$newImei2")

            val result = ImeiChangerUtil.writeImei(applicationContext, newImei1, newImei2)

            val db = AppDatabase.getDatabase(applicationContext)
            db.imeiHistoryDao().insert(
                ImeiHistory(
                    timestamp = System.currentTimeMillis(),
                    sim1OldImei = oldImei1,
                    sim1NewImei = newImei1,
                    sim2OldImei = oldImei2,
                    sim2NewImei = newImei2,
                    method = "Auto-rotation",
                    success = result.status == "OK",
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                )
            )

            val success = result.status == "OK"
            val lang = settings.selectedLanguage.first()
            val trigger = when (lang) {
                "ru" -> "Авто"
                "uk" -> "Авто"
                "de" -> "Auto"
                "pl" -> "Auto"
                "es" -> "Auto"
                "lt" -> "Auto"
                "lv" -> "Auto"
                else -> "Auto"
            }
            showRotationNotification(applicationContext, success, trigger, newImei1, newImei2)

            if (success) {
                Log.d(TAG, "Auto-rotation successful")
                Result.success()
            } else {
                Log.w(TAG, "Auto-rotation failed: ${result.newWifiMac}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-rotation error", e)
            Result.retry()
        }
    }
}
