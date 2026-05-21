package com.android.imeisettings.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.provider.Settings
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.ServiceCompat
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.imeisettings.MainActivity
import com.android.imeisettings.R
import com.android.imeisettings.data.repository.NetworkStateTracker
import com.android.imeisettings.ui.theme.CONSULIMEITheme

class EmergencyOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private val mViewModelStore = ViewModelStore()
    private val mSavedStateRegistryController = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore get() = mViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = mSavedStateRegistryController.savedStateRegistry

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val TYPE_EMERGENCY = "emergency"
        const val TYPE_ASN_CHECK = "asn_check"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_LANG = "extra_lang"
        const val EXTRA_CELL_ID = "extra_cell_id"
        const val EXTRA_ZONE_ID = "extra_zone_id"
        const val EXTRA_THREAT_LEVEL = "extra_threat_level"
        const val EXTRA_RSSI = "extra_rssi"
        const val EXTRA_LAC_TAC = "extra_lac_tac"
        const val EXTRA_NETWORK_TYPE = "extra_network_type"
        const val EXTRA_ENCRYPTION = "extra_encryption"
        const val EXTRA_NEIGHBORS = "extra_neighbors"
        
        private const val CHANNEL_ID = "EmergencyOverlayChannel"
        private const val NOTIFICATION_ID = 3
    }

    private val translations = mapOf(
        "en" to "CRITICAL THREAT DETECTED. Your communications may be compromised. REASON:",
        "uk" to "ВИЯВЛЕНО КРИТИЧНУ ЗАГРОЗУ. Ваші комунікації можуть бути скомпрометовані. ПРИЧИНА:",
        "ru" to "ОБНАРУЖЕНА КРИТИЧЕСКАЯ УГРОЗА. Ваши коммуникации могут быть скомпрометированы. ПРИЧИНА:",
        "de" to "KRITISCHE BEDROHUNG ERKANNT. Ihre Kommunikation könnte kompromittiert sein. GRUND:",
        "pl" to "WYKRYTO KRYTYCZNE ZAGROŻENIE. Twoja komunikacja może być narażona. POWÓD:",
        "lt" to "APTIKTA KRITINĖ GRĖSMĖ. Jūsų ryšiai gali būti pažeisti. PRIEŽASTIS:",
        "lv" to "KONSTATĒTS KRITISKS DRAUDS. Jūsu sakari var būt kompromitēti. IEMESLS:",
        "es" to "AMENAZA CRÍTICA DETECTADA. Sus comunicaciones pueden estar comprometidas. MOTIVO:"
    )

    private val asnTranslations = mapOf(
        "en" to "Network route (ASN) has changed. This could indicate a redirection or interception. Do you trust this network?",
        "ru" to "Маршрут сети (ASN) изменился. Это может указывать на перенаправление или перехват. Вы доверяете этой сети?",
        "uk" to "Маршрут мережі (ASN) змінився. Це може свідчити про перенаправлення або перехоплення. Ви довіряєте цій мережі?",
        "de" to "Die Netzwerkroute (ASN) hat sich geändert. Dies könnte auf eine Umleitung oder ein Abfangen hinweisen. Vertrauen Sie diesem Netzwerk?",
        "pl" to "Trasa sieciowa (ASN) uległa zmianie. Może to oznaczać przekierowanie lub przechwycenie. Czy ufasz tej sieci?",
        "lt" to "Tinklo maršrutas (ASN) pasikeitė. Tai gali reikšti peradresavimą arba perėmimą. Ar pasitikite šiuo tinklu?",
        "lv" to "Tīkla maršruts (ASN) ir mainījies. Tas var norādīt uz novirzīšanu vai pārtveršanu. Vai uzticaties šim tīklam?",
        "es" to "La ruta de red (ASN) ha cambiado. Esto podría indicar una redirección o interceptación. ¿Confías en esta red?"
    )

    override fun onCreate() {
        super.onCreate()
        mSavedStateRegistryController.performRestore(null)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical alerts for network security"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Security Alert")
            .setContentText("Emergency overlay active.")
            .setSmallIcon(R.drawable.ic_notification_dot)
            .setColor(Color.Red.hashCode())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: TYPE_EMERGENCY
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "SECURITY ALERT"
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "Unknown anomaly"
        val lang = intent?.getStringExtra(EXTRA_LANG) ?: "en"
        val cellId = intent?.getStringExtra(EXTRA_CELL_ID)
        val zoneId = intent?.getIntExtra(EXTRA_ZONE_ID, -1) ?: -1
        val threatLevel = intent?.getIntExtra(EXTRA_THREAT_LEVEL, -1) ?: -1
        val rssi = intent?.getIntExtra(EXTRA_RSSI, -140) ?: -140
        val lacTac = intent?.getStringExtra(EXTRA_LAC_TAC) ?: "---"
        val networkType = intent?.getStringExtra(EXTRA_NETWORK_TYPE) ?: "N/A"
        val encryption = intent?.getStringExtra(EXTRA_ENCRYPTION) ?: "N/A"
        val neighbors = intent?.getIntExtra(EXTRA_NEIGHBORS, -1) ?: -1
        
        startAlertEffects(isEmergency = type == TYPE_EMERGENCY)
        showOverlay(type, title, reason, lang, cellId, zoneId, threatLevel, rssi, lacTac, networkType, encryption, neighbors)

        // Auto-stop alert effects after 60 seconds to prevent battery drain
        android.os.Handler(mainLooper).postDelayed({ stopAlertEffects() }, 60_000L)

        return START_NOT_STICKY
    }

    private fun startAlertEffects(isEmergency: Boolean) {
        try {
            // Звук только для критических угроз. ASN алерт остается бесшумным по запросу.
            if (isEmergency) {
                val resId = resources.getIdentifier("alert", "raw", packageName)
                if (resId != 0) {
                    mediaPlayer = MediaPlayer.create(this, resId).apply {
                        isLooping = true
                        start()
                    }
                } else {
                    val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(applicationContext, alertUri)
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                        isLooping = true
                        prepare()
                        start()
                    }
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = if (isEmergency) longArrayOf(0, 500, 200, 500, 200) else longArrayOf(0, 200, 100, 200)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, if (isEmergency) 0 else -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (isEmergency) longArrayOf(0, 500, 200) else longArrayOf(0, 200), if (isEmergency) 0 else -1)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopAlertEffects() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showOverlay(type: String, title: String, reason: String, lang: String, cellId: String?, zoneId: Int,
                             threatLevel: Int = -1, rssi: Int = -140, lacTac: String = "---",
                             networkType: String = "N/A", encryption: String = "N/A", neighbors: Int = -1) {
        // Check if overlay permission is granted; fallback to full-screen notification if not
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showFullScreenNotification(type, title, reason, lang)
            return
        }

        if (overlayView != null) {
            try { windowManager.removeView(overlayView) } catch (e: Exception) {}
            overlayView = null
        }

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { 
            screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Настройка для некоторых OEM-оболочек (MIUI и т.д.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@EmergencyOverlayService)
            setViewTreeViewModelStoreOwner(this@EmergencyOverlayService)
            setViewTreeSavedStateRegistryOwner(this@EmergencyOverlayService)
            setContent {
                CONSULIMEITheme(darkTheme = true) {
                    if (type == TYPE_ASN_CHECK) {
                        val alertText = asnTranslations[lang] ?: asnTranslations["en"]!!
                        AsnChangeContent(title, reason, alertText, 
                            onConfirm = {
                                stopAlertEffects()
                                stopSelf()
                            },
                            onCancel = {
                                stopAlertEffects()
                                // Optionally do more here, but for now just dismiss
                                stopSelf()
                            }
                        )
                    } else {
                        val alertText = translations[lang] ?: translations["en"]!!
                        EmergencyAlertContent(
                            title = title, reason = reason, alertText = alertText,
                            cellId = cellId ?: "---", lacTac = lacTac,
                            rssi = rssi, threatLevel = threatLevel,
                            networkType = networkType, encryption = encryption,
                            neighbors = neighbors, lang = lang
                        ) {
                            stopAlertEffects()
                            NetworkStateTracker.resetThreatLevel()
                            val launchIntent = Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("GO_TO_SECURITY", true)
                                if (cellId != null && zoneId != -1) {
                                    putExtra("PENDING_CELL_ID", cellId)
                                    putExtra("PENDING_ZONE_ID", zoneId)
                                }
                            }
                            context.startActivity(launchIntent)
                            stopSelf()
                        }
                    }
                }
            }
        }
        try { windowManager.addView(overlayView, params) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        stopAlertEffects()
        overlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * Fallback when SYSTEM_ALERT_WINDOW permission is not granted.
     * Shows a high-priority full-screen intent notification instead of overlay.
     */
    private fun showFullScreenNotification(type: String, title: String, reason: String, lang: String) {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alert_type", type)
            putExtra("alert_title", title)
            putExtra("alert_reason", reason)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 100, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isEmergency = type == TYPE_EMERGENCY
        val alertText = if (isEmergency) {
            translations[lang] ?: translations["en"]!!
        } else {
            asnTranslations[lang] ?: asnTranslations["en"]!!
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$alertText $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$alertText\n\n$reason"))
            .setSmallIcon(R.drawable.ic_notification_dot)
            .setColor(android.graphics.Color.RED)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID + 10, notification)
    }
}

@Composable
fun AsnChangeContent(title: String, reason: String, alertText: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Rounded.NetworkCheck, null, tint = Color.Yellow, modifier = Modifier.size(80.dp))
            Spacer(modifier = Modifier.height(32.dp))
            Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.Yellow, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = alertText, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Color.White, lineHeight = 24.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Surface(color = Color(0xFF202000), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(text = reason, color = Color.Yellow, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(48.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onConfirm, 
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), 
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Подтвердить", fontWeight = FontWeight.Bold, color = Color.White)
                }
                OutlinedButton(
                    onClick = onCancel, 
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.Red),
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Отменить", fontWeight = FontWeight.Bold, color = Color.Red)
                }
            }
        }
    }
}

@Composable
fun EmergencyAlertContent(
    title: String, reason: String, alertText: String,
    cellId: String = "---", lacTac: String = "---",
    rssi: Int = -140, threatLevel: Int = -1,
    networkType: String = "N/A", encryption: String = "N/A",
    neighbors: Int = -1, lang: String = "en",
    onDismiss: () -> Unit
) {
    val recommendations = buildRecommendations(reason, networkType, encryption, neighbors, lang)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.97f)), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp)
                .fillMaxSize()
                .padding(top = 40.dp)
        ) {
            Icon(Icons.Rounded.Warning, null, tint = Color.Red, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Color.Red, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = alertText, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.9f), lineHeight = 20.sp)

            Spacer(modifier = Modifier.height(16.dp))

            // Attack details card
            Surface(color = Color(0xFF1A0000), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (lang == "ru") "ДЕТАЛИ АТАКИ" else if (lang == "uk") "ДЕТАЛІ АТАКИ" else "ATTACK DETAILS",
                        color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val detailRows = mutableListOf<Pair<String, String>>()
                    if (threatLevel >= 0) detailRows.add((if (lang == "ru") "Уровень угрозы" else "Threat Level") to "$threatLevel%")
                    if (cellId != "---") detailRows.add("Cell ID" to cellId)
                    if (lacTac != "---") detailRows.add("LAC/TAC" to lacTac)
                    if (rssi > -140) detailRows.add("RSSI" to "${rssi} dBm")
                    if (networkType != "N/A") detailRows.add((if (lang == "ru") "Тип сети" else "Network") to networkType)
                    if (encryption != "N/A") detailRows.add((if (lang == "ru") "Шифрование" else "Encryption") to encryption)
                    if (neighbors >= 0) detailRows.add((if (lang == "ru") "Соседние соты" else "Neighbors") to neighbors.toString())

                    detailRows.forEach { (label, value) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(text = "$label:", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                text = value, color = when {
                                    label.contains("Threat") || label.contains("угроз") -> when {
                                        threatLevel >= 75 -> Color.Red
                                        threatLevel >= 50 -> Color.Yellow
                                        else -> Color.Green
                                    }
                                    label.contains("Encrypt") || label.contains("Шифр") -> {
                                        if (encryption.contains("NONE") || encryption.contains("A5/0") || encryption.contains("EEA0")) Color.Red else Color.Green
                                    }
                                    else -> Color.White
                                },
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Reason details
            Surface(color = Color(0xFF200000), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (lang == "ru") "ПРИЧИНА" else if (lang == "uk") "ПРИЧИНА" else "REASON",
                        color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = reason, color = Color(0xFFFF6666), fontSize = 13.sp, lineHeight = 18.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Recommendations card
            if (recommendations.isNotEmpty()) {
                Surface(color = Color(0xFF001A00), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (lang == "ru") "РЕКОМЕНДАЦИИ" else if (lang == "uk") "РЕКОМЕНДАЦІЇ" else "RECOMMENDATIONS",
                            color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        recommendations.forEach { rec ->
                            Text(text = rec, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp,
                                modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (lang == "ru") "ПОНЯТНО" else if (lang == "uk") "ЗРОЗУМІЛО" else "OK",
                    fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun buildRecommendations(reason: String, networkType: String, encryption: String, neighbors: Int, lang: String): List<String> {
    val recs = mutableListOf<String>()
    val isRu = lang == "ru" || lang == "uk"

    if (encryption.contains("NONE") || encryption.contains("A5/0") || encryption.contains("EEA0")) {
        recs.add(if (isRu) "\u26A0 Шифрование отключено! Не совершайте звонки и не отправляйте SMS" else "\u26A0 Encryption disabled! Do not make calls or send SMS")
    }
    if (networkType == "GSM") {
        recs.add(if (isRu) "\u26A0 Принудительный переход на 2G. Установите LTE Only в настройках сети" else "\u26A0 Forced 2G downgrade. Set LTE Only in network settings")
    }
    if (neighbors == 0) {
        recs.add(if (isRu) "\u26A0 Нет соседних сот — признак изолированной ложной БС" else "\u26A0 No neighbor cells — sign of isolated fake base station")
    }
    if (reason.contains("FBS", ignoreCase = true) || reason.contains("Fake", ignoreCase = true)) {
        recs.add(if (isRu) "\u2022 Включите авиарежим на 30 секунд, затем выключите" else "\u2022 Enable airplane mode for 30 seconds, then disable")
        recs.add(if (isRu) "\u2022 Переместитесь на 500+ метров от текущего местоположения" else "\u2022 Move 500+ meters from current location")
    }
    if (reason.contains("LAC", ignoreCase = true) || reason.contains("ARFCN", ignoreCase = true)) {
        recs.add(if (isRu) "\u2022 Параметры сотовой вышки изменились — возможна подмена" else "\u2022 Cell tower parameters changed — possible spoofing")
    }
    if (reason.contains("Signal jump", ignoreCase = true) || reason.contains("isolation", ignoreCase = true)) {
        recs.add(if (isRu) "\u2022 Резкий скачок сигнала — типичный признак IMSI-catcher" else "\u2022 Sudden signal jump — typical IMSI catcher indicator")
    }
    recs.add(if (isRu) "\u2022 Используйте VPN и мессенджеры с шифрованием (Signal, Telegram)" else "\u2022 Use VPN and encrypted messengers (Signal, Telegram)")
    recs.add(if (isRu) "\u2022 Не совершайте конфиденциальные звонки до разрешения ситуации" else "\u2022 Do not make sensitive calls until situation is resolved")

    return recs
}
