package com.android.imeisettings.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.imeisettings.R
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.SecurityLog
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.data.model.NetworkDetails
import com.android.imeisettings.data.repository.AsnDatabase
import com.android.imeisettings.data.repository.AsnRepository
import com.android.imeisettings.data.repository.NetworkStateTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors
import android.content.pm.ServiceInfo
import com.android.imeisettings.MainActivity

class NetworkSecurityService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var powerManager: PowerManager
    private lateinit var db: AppDatabase
    private lateinit var settingsDataStore: SettingsDataStore
    private val asnRepository = AsnRepository()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var lastThreatLevel = -1
    private var lastAsnNumber = ""
    private var lastAsnInfo = "Loading..."
    private var lastAlertedCellId = ""
    private var lastCellIdSim1 = ""
    private var lastCellIdSim2 = ""
    private var lastLogTime = 0L
    private var lastShownContent = ""
    private var lastShownThreatLevel = -1
    private var inMemorySafeZones: List<com.android.imeisettings.data.local.SafeZone> = emptyList()
    private var wakeLock: PowerManager.WakeLock? = null
    private var isCallActive = false
    private var registeredTelephonyCallback: TelephonyCallback? = null
    private var radioLogMonitor: RadioLogMonitor? = null
    private var lastCleanupTime = 0L
    private var lastNetworkMccMnc = ""
    private var lastAlertTime = 0L
    private var forensicLowCount = 0
    private var lastScanCellId = "---"
    private var lastScanLacTac = "---"
    private var lastScanDbm = -140
    private var lastScanNetworkType = "N/A"
    private var lastScanEncryption = "N/A"
    private var lastScanNeighbors = -1

    companion object {
        private const val CHANNEL_ID = "NetworkSecurityChannelV3"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "CONSUL_DEBUG"
        private const val ACTION_FORENSIC = "com.android.imeisettings.FORENSIC_EVENT"
        const val ACTION_KILL_SWITCH = "com.android.imeisettings.KILL_SWITCH_TOGGLE"
    }

    private val reasonsTrans = mapOf(
        "en" to mapOf(
            "gsm" to "2G Mode detected (higher vulnerability)",
            "neighbors_0_strong" to "No neighbors with strong signal (Potential Fake Tower)",
            "neighbors_0_weak" to "No neighboring cells detected",
            "cipher_off" to "CRITICAL: Encryption is DISABLED by network",
            "asn_err" to "ASN Warning: Unexpected network provider route",
            "sec_threat" to "SECURITY THREAT",
            "secure" to "System Secure | Monitoring...",
            "threat_detected" to "⚠️ NETWORK ANOMALY DETECTED",
            "no_sim" to "No active SIM cards detected",
            "silent_sms" to "Silent SMS (Type-0) Blocked!",
            "geofence_violation" to "UNKNOWN TOWER DETECTED IN SAFE AREA!",
            "log_scan" to "Scan",
            "log_sim" to "SIM",
            "log_threat" to "Threat",
            "silent_call" to "SILENT CALL DETECTED: Stealth tracking attempt.",
            "t_gsm" to "2G (Vulnerability)",
            "neighbors_0_weak_log" to "No neighbors",
            "t_cipher" to "Unsecured",
            "t_geo" to "Foreign Tower",
            "t_asn" to "ASN Anomaly",
            "state_in_service" to "IN SERVICE",
            "state_searching" to "SEARCHING...",
            "t_5g_nr_suspect" to "5G NR Anomaly",
            "t_rrc_redirect" to "RRC Redirect Attack",
            "t_new_tower" to "New Tower",
            "t_5g_spoof" to "5G Spoofed"
        ),
        "uk" to mapOf(
            "gsm" to "Виявлено режим 2G (вища вразливість)",
            "neighbors_0_strong" to "Немає сусідів при сильному сигналі (Ризик фейкової вишки)",
            "neighbors_0_weak" to "Сусідні соти не виявлені",
            "cipher_off" to "КРИТИЧНО: Шифрування ВИМКНЕНО мережею",
            "asn_err" to "Увага ASN: Нетиповий маршрут провайдера",
            "sec_threat" to "ЗАГРОЗА БЕЗПЕЦІ",
            "secure" to "Система безпечна | Моніторинг...",
            "threat_detected" to "⚠️ ВИЯВЛЕНО АНОМАЛІЮ МЕРЕЖІ",
            "no_sim" to "Активних SIM-карт не виявлено",
            "silent_sms" to "Silent SMS (Type-0) заблоковано!",
            "geofence_violation" to "НЕВІДОМА ВИШКА В БЕЗПЕЧНІЙ ЗОНІ!",
            "log_scan" to "Сканування",
            "log_sim" to "SIM",
            "log_threat" to "Загроза",
            "silent_call" to "ВИЯВЛЕНО ТИХИЙ ДЗВІНОК: Спроба прихованого стеження.",
            "t_gsm" to "2G (Вразливість)",
            "neighbors_0_weak_log" to "Немає сусідів",
            "t_cipher" to "Без шифрування",
            "t_geo" to "Чужа вишка",
            "t_asn" to "ASN Аномалія",
            "state_in_service" to "В МЕРЕЖІ",
            "state_searching" to "ПОШУК...",
            "t_5g_nr_suspect" to "5G NR Аномалія",
            "t_rrc_redirect" to "Атака RRC Redirect",
            "t_new_tower" to "Нова вишка",
            "t_5g_spoof" to "5G Підробка"
        ),
        "ru" to mapOf(
            "gsm" to "Обнаружен режим 2G (высокая уязвимость перед IMSI-ловушками)",
            "neighbors_0_strong" to "КРИТИЧЕСКИ: Сильный сигнал при полном отсутствии соседних сот. 100% признак фейковой БС!",
            "neighbors_0_weak" to "Внимание: Соседние соты не обнаружены. Сеть может быть изолирована.",
            "cipher_off" to "КРИТИЧЕСКИ: Шифрование канала связи ОТКЛЮЧЕНО оператором или ловушкой!",
            "asn_err" to "Аномалия трафика: Нетипичный маршрут провайдера (возможен перехват данных)",
            "sec_threat" to "УГРОЗА БЕЗОПАСНОСТИ",
            "secure" to "Система в безопасности | Мониторинг активен...",
            "threat_detected" to "⚠️ ОБНАРУЖЕНА СЕТЕВАЯ АНОМАЛИЯ",
            "no_sim" to "Активные SIM-карты не обнаружены",
            "silent_sms" to "Silent SMS (Type-0) заблоковано! Попытка скрытой триангуляции.",
            "geofence_violation" to "КРИТИЧЕСКИ: Неизвестная вышка обнаружена в вашей 'Безопасной зоне'!",
            "log_scan" to "Сканирование",
            "log_sim" to "SIM",
            "log_threat" to "Угроза",
            "silent_call" to "ОБНАРУЖЕН SILENT CALL: Попытка скрытого замера координат (Ping).",
            "t_gsm" to "2G (Уязвимость)",
            "neighbors_0_weak_log" to "Нет соседей",
            "t_cipher" to "Без шифрования",
            "t_geo" to "Чужая вышка",
            "t_asn" to "ASN Аномалия",
            "state_in_service" to "В СЕТИ",
            "state_searching" to "ПОИСК...",
            "t_5g_nr_suspect" to "5G NR Аномалия",
            "t_rrc_redirect" to "Атака RRC Redirect",
            "t_new_tower" to "Новая вышка",
            "t_5g_spoof" to "5G Подделка"
        ),
        "de" to mapOf(
            "gsm" to "2G-Modus erkannt (höhere Verwundbarkeit)",
            "neighbors_0_strong" to "Keine Nachbarn bei starkem Signal (Verdacht auf Fake-Tower)",
            "neighbors_0_weak" to "Keine benachbarten Funkzellen erkannt",
            "cipher_off" to "KRITISCH: Verschlüsselung ist DEAKTIVIERT",
            "asn_err" to "ASN-Warnung: Unerwartete Netzwerkroute",
            "sec_threat" to "SICHERHEITSBEDROHUNG",
            "secure" to "System sicher | Überwachung...",
            "threat_detected" to "⚠️ NETZWERKANOMALIE ERKANNT",
            "no_sim" to "Keine aktiven SIM-Karten erkannt",
            "silent_sms" to "Stille SMS (Typ-0) blockiert!",
            "geofence_violation" to "UNBEKANNTER FUNKMAST IN SICHERER ZONE ERKANNT!",
            "log_scan" to "Scan",
            "log_sim" to "SIM",
            "log_threat" to "Bedrohung",
            "silent_call" to "STILLER ANRUF ERKANNT: Ortungsversuch.",
            "t_gsm" to "2G (Schwachstelle)",
            "neighbors_0_weak_log" to "Keine Nachbarn",
            "t_cipher" to "Unverschlüsselt",
            "t_geo" to "Fremder Mast",
            "t_asn" to "ASN Anomalie",
            "state_in_service" to "IN BETRIEB",
            "state_searching" to "SUCHE...",
            "t_5g_nr_suspect" to "5G NR Anomalie",
            "t_rrc_redirect" to "RRC-Umleitungsangriff",
            "t_new_tower" to "Neuer Mast",
            "t_5g_spoof" to "5G-Spoofing"
        ),
        "pl" to mapOf(
            "gsm" to "Wykryto tryb 2G (wyższa podatność)",
            "neighbors_0_strong" to "Brak sąsiadów przy silnym sygnale (Ryzyko fałszywej wieży)",
            "neighbors_0_weak" to "Nie wykryto sąsiadujących komórek",
            "cipher_off" to "KRYTYCZNE: Szyfrowanie WYŁĄCZONE przez sieć",
            "asn_err" to "Ostrzeżenie ASN: Nieoczekiwana trasa operatora",
            "sec_threat" to "ZAGROŻENIE BEZPIECZEŃSTWA",
            "secure" to "System bezpieczny | Monitorowanie...",
            "threat_detected" to "⚠️ WYKRYTO ANOMALIĘ SIECIOWĄ",
            "no_sim" to "Brak aktywnych kart SIM",
            "silent_sms" to "Cichy SMS (Typ-0) zablokowany!",
            "geofence_violation" to "NIEZNANA WIEŻA W BEZPIECZNEJ STREFIE!",
            "log_scan" to "Skanowanie",
            "log_sim" to "SIM",
            "log_threat" to "Zagrożenie",
            "silent_call" to "WYKRYTO CICHE POŁĄCZENIE: Próba śledzenia.",
            "t_gsm" to "2G (Podatność)",
            "neighbors_0_weak_log" to "Brak sąsiadów",
            "t_cipher" to "Brak szyfrowania",
            "t_geo" to "Obca wieża",
            "t_asn" to "Anomalia ASN",
            "state_in_service" to "W SIECI",
            "state_searching" to "SZUKANIE...",
            "t_5g_nr_suspect" to "Anomalia 5G NR",
            "t_rrc_redirect" to "Atak RRC Redirect",
            "t_new_tower" to "Nowa wieża",
            "t_5g_spoof" to "Spoofing 5G"
        ),
        "es" to mapOf(
            "gsm" to "Modo 2G detectado (mayor vulnerabilidad)",
            "neighbors_0_strong" to "Sin vecinos con señal fuerte (Posible torre falsa)",
            "neighbors_0_weak" to "No se detectaron celdas vecinas",
            "cipher_off" to "CRÍTICO: El cifrado está DESACTIVADO por la red",
            "asn_err" to "Advertencia ASN: Ruta de red inesperada",
            "sec_threat" to "AMENAZA DE SEGURIDAD",
            "secure" to "Sistema Seguro | Monitoreando...",
            "threat_detected" to "⚠️ ANOMALÍA DE RED DETECTADA",
            "no_sim" to "No se detectaron tarjetas SIM activas",
            "silent_sms" to "¡SMS Silencioso (Tipo-0) Bloqueado!",
            "geofence_violation" to "¡TORRE DESCONOCIDA EN ZONA SEGURA!",
            "log_scan" to "Escaneo",
            "log_sim" to "SIM",
            "log_threat" to "Amenaza",
            "silent_call" to "LLAMADA SILENCIOSA: Intento de rastreo.",
            "t_gsm" to "2G (Vulnerabilidad)",
            "neighbors_0_weak_log" to "Sin vecinos",
            "t_cipher" to "Sin cifrado",
            "t_geo" to "Torre extraña",
            "t_asn" to "Anomalía ASN",
            "state_in_service" to "EN SERVICIO",
            "state_searching" to "BUSCANDO...",
            "t_5g_nr_suspect" to "Anomalía 5G NR",
            "t_rrc_redirect" to "Ataque RRC Redirect",
            "t_new_tower" to "Nueva torre",
            "t_5g_spoof" to "Suplantación 5G"
        ),
        "lt" to mapOf(
            "gsm" to "Aptiktas 2G režimas (didesnis pažeidžiamumas)",
            "neighbors_0_strong" to "Nėra kaimynų esant stipriam signalui (Galimas netikras bokštas)",
            "neighbors_0_weak" to "Gretimų celių neaptikta",
            "cipher_off" to "KRITIŠKA: Tinklas IŠJUNGĖ šifravimą",
            "asn_err" to "ASN įspėjimas: Netikėtas tinklo maršrutas",
            "sec_threat" to "SAUGUMO GRĖSMĖ",
            "secure" to "Sistema saugi | Stebima...",
            "threat_detected" to "⚠️ APTIKTA TINKLO ANOMALIJA",
            "no_sim" to "Aktyvių SIM kortelių nerasta",
            "silent_sms" to "Tylusis SMS (0 tipo) užblokuotas!",
            "geofence_violation" to "NEŽINOMAS BOKŠTAS SAUGIOJE ZONOJE!",
            "log_scan" to "Skenavimas",
            "log_sim" to "SIM",
            "log_threat" to "Grėsmė",
            "silent_call" to "APTIKTAS TYLUS SKAMBUTIS: Sekimo bandymas.",
            "t_gsm" to "2G (Pažeidžiamumas)",
            "neighbors_0_weak_log" to "Nėra kaimynų",
            "t_cipher" to "Be šifravimo",
            "t_geo" to "Svetimas bokštas",
            "t_asn" to "ASN anomalija",
            "state_in_service" to "RYŠYJE",
            "state_searching" to "IEŠKOMA...",
            "t_5g_nr_suspect" to "5G NR Anomalija",
            "t_rrc_redirect" to "RRC Peradresavimo ataka",
            "t_new_tower" to "Naujas bokštas",
            "t_5g_spoof" to "5G Klastojimas"
        ),
        "lv" to mapOf(
            "gsm" to "Atklāts 2G režīms (augstāka ievainojamība)",
            "neighbors_0_strong" to "Nav kaimiņu ar spēcīgu signālu (Iespējams viltus tornis)",
            "neighbors_0_weak" to "Blakus esošās šūnas nav atklātas",
            "cipher_off" to "KRITISKI: Tīkls ir ATSLĒDZIS šifrēšanu",
            "asn_err" to "ASN brīdinājums: Neparedzēts tīkla maršruts",
            "sec_threat" to "DROŠĪBAS DRAUDI",
            "secure" to "Sistēma droša | Uzraudzība...",
            "threat_detected" to "⚠️ ATKLĀTA TĪKLA ANOMĀLIJA",
            "no_sim" to "Aktīvas SIM kartes nav atrastas",
            "silent_sms" to "Klusais SMS (Tips-0) bloķēts!",
            "geofence_violation" to "NEZINĀMS TORNIS DROŠAJĀ ZONĀ!",
            "log_scan" to "Skenēšana",
            "log_sim" to "SIM",
            "log_threat" to "Draudi",
            "silent_call" to "ATKLĀTS KLUSAIS ZVANS: Izsekošanas mēģinājums.",
            "t_gsm" to "2G (Ievainojamība)",
            "neighbors_0_weak_log" to "Nav kaimiņu",
            "t_cipher" to "Bez šifrēšanas",
            "t_geo" to "Svešs tornis",
            "t_asn" to "ASN anomālija",
            "state_in_service" to "TĪKLĀ",
            "state_searching" to "MEKLĒ...",
            "t_5g_nr_suspect" to "5G NR Anomālija",
            "t_rrc_redirect" to "RRC Novirzīšanas uzbrukums",
            "t_new_tower" to "Jauns tornis",
            "t_5g_spoof" to "5G Viltošana"
        )
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch { 
                val lang = settingsDataStore.selectedLanguage.first()
                fetchAsnData(lang)
                updateNetworkData(lang)
                handleNetworkChangeRotation()
            }
        }
        override fun onLost(network: Network) {
            serviceScope.launch { 
                val lang = settingsDataStore.selectedLanguage.first()
                lastAsnInfo = "Loading..."
                lastAsnNumber = ""
                updateNetworkData(lang) 
            }
        }
    }

    private val simStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            serviceScope.launch {
                val action = intent?.action

                if (action == ACTION_KILL_SWITCH) {
                    try {
                        com.android.imeisettings.util.KillSwitchController.triggerKillSwitch(this@NetworkSecurityService)
                        val lang = settingsDataStore.selectedLanguage.first()
                        db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "KILL_SWITCH", "Manually activated via notification"))
                        updateNetworkData(lang)
                    } catch (e: Exception) {
                        Log.e(TAG, "Kill switch error: ${e.message}")
                    }
                    return@launch
                }

                if (action == ACTION_FORENSIC) {
                    val threat = intent.getIntExtra("threat", 0)
                    val reason = intent.getStringExtra("reason") ?: "Unknown"
                    val lang = settingsDataStore.selectedLanguage.first()
                    
                    db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "FORENSIC", reason))
                    NetworkStateTracker.forceForensicThreat(threat, reason)
                    if (threat >= 76) triggerEmergencyOverlay("ATTACK DETECTED", reason, lang)
                }

                // Auto-rotate on SIM/network change
                if (action == "android.intent.action.SIM_STATE_CHANGED" || action == "android.intent.action.SERVICE_STATE") {
                    handleNetworkChangeRotation()
                }

                val lang = settingsDataStore.selectedLanguage.first()
                updateNetworkData(lang)
            }
        }
    }

    private suspend fun handleNetworkChangeRotation() {
        try {
            val rotateOnNetworkChange = settingsDataStore.autoRotateOnNetworkChange.first()
            if (!rotateOnNetworkChange) return

            val activeSubs = try { subscriptionManager.activeSubscriptionInfoList ?: emptyList() } catch (e: Exception) { emptyList() }
            val currentMccMnc = activeSubs.firstOrNull()?.let { "${it.mccString}${it.mncString}" } ?: return

            if (lastNetworkMccMnc.isNotEmpty() && currentMccMnc != lastNetworkMccMnc) {
                Log.d(TAG, "Network changed ($lastNetworkMccMnc -> $currentMccMnc), triggering IMEI rotation")
                db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "ROTATION", "Network change detected: $lastNetworkMccMnc -> $currentMccMnc, rotating IMEI"))

                // Read old IMEI BEFORE writing new values
                val oldImei1 = com.android.imeisettings.util.DeviceIdentifierUtil.getImei(this@NetworkSecurityService, 0)
                val oldImei2 = com.android.imeisettings.util.DeviceIdentifierUtil.getImei(this@NetworkSecurityService, 1)

                val newImei1 = com.android.imeisettings.util.ImeiGenerator.generateImei()
                val newImei2 = com.android.imeisettings.util.ImeiGenerator.generateImei()

                // Use full writeImei flow for consistency (validation, Xposed, MAC/ID, finalizeCommit)
                val result = com.android.imeisettings.util.ImeiChangerUtil.writeImei(this@NetworkSecurityService, newImei1, newImei2)

                db.imeiHistoryDao().insert(com.android.imeisettings.data.local.ImeiHistory(
                    timestamp = System.currentTimeMillis(),
                    sim1OldImei = oldImei1, sim1NewImei = newImei1,
                    sim2OldImei = oldImei2, sim2NewImei = newImei2,
                    method = "Network-change rotation",
                    success = result.status == "OK",
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                ))

                val success = result.status == "OK"
                val lang = settingsDataStore.selectedLanguage.first()
                val trigger = when (lang) {
                    "ru" -> "Смена сети"
                    "uk" -> "Зміна мережі"
                    "de" -> "Netzwechsel"
                    "pl" -> "Zmiana sieci"
                    "es" -> "Cambio de red"
                    "lt" -> "Tinklo keitimas"
                    "lv" -> "Tīkla maiņa"
                    else -> "Network"
                }
                ImeiRotationWorker.showRotationNotification(
                    this@NetworkSecurityService, success, trigger, newImei1, newImei2
                )

                if (success) {
                    Log.d(TAG, "Network-change rotation successful")
                } else {
                    Log.w(TAG, "Network-change rotation failed: ${result.newWifiMac}")
                }
            }
            lastNetworkMccMnc = currentMccMnc
        } catch (e: Exception) {
            Log.e(TAG, "Network change rotation error: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        val initialNotification = createNotification("Security service starting...", -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        db = AppDatabase.getDatabase(this)
        settingsDataStore = SettingsDataStore.getInstance(this)

        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CONSUL:SecurityLock").apply {
            setReferenceCounted(false)
        }

        val filter = IntentFilter().apply {
            addAction("android.intent.action.SIM_STATE_CHANGED")
            addAction("android.intent.action.SERVICE_STATE")
            addAction(ACTION_FORENSIC)
            addAction(ACTION_KILL_SWITCH)
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(simStateReceiver, filter, RadioLogMonitor.PERMISSION_FORENSIC, null, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(simStateReceiver, filter, RadioLogMonitor.PERMISSION_FORENSIC, null)
        }
        
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        serviceScope.launch {
            db.safeZoneDao().getAllSafeZonesFlow().collect { zones ->
                inMemorySafeZones = zones
            }
        }

        // Start RadioLogMonitor if enabled
        serviceScope.launch {
            val radioEnabled = settingsDataStore.radioLogMonitorEnabled.first()
            if (radioEnabled) {
                startRadioLogMonitor()
            }
        }

        // Start BLE Wear Alert service only if enabled in settings
        serviceScope.launch {
            val bleEnabled = settingsDataStore.wearBleAlertEnabled.first()
            if (bleEnabled) {
                try {
                    WearAlertService.start(this@NetworkSecurityService)
                    Log.i(TAG, "BLE Wear Alert started (enabled in settings)")
                } catch (e: Exception) {
                    Log.w(TAG, "BLE Wear Alert not available: ${e.message}")
                }
            }
        }

        startMonitoring()
    }

    private fun startRadioLogMonitor() {
        if (radioLogMonitor == null) {
            radioLogMonitor = RadioLogMonitor(this)
        }
        radioLogMonitor?.start()
        Log.d(TAG, "RadioLogMonitor started")
    }

    private fun stopRadioLogMonitor() {
        radioLogMonitor?.stop()
        radioLogMonitor = null
        Log.d(TAG, "RadioLogMonitor stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "CONSUL Guard Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows protection status in the status bar"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String, threat: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val color = when {
            threat < 0 -> Color.GRAY
            threat >= 50 -> Color.RED
            threat >= 25 -> Color.YELLOW
            else -> Color.GREEN
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CONSUL Guard ACTIVE")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_dot)
            .setColor(color).setColorized(true).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private suspend fun fetchAsnData(lang: String) {
        try {
            val asnRes = asnRepository.getAsnInfo()
            if (asnRes != null) {
                val newAsn = asnRes.asn ?: ""
                val newOrg = AsnDatabase.trustedAsns[newAsn] ?: asnRes.org ?: "Unknown"
                
                val userTrusted = settingsDataStore.userTrustedAsns.first()
                if (lastAsnNumber.isNotEmpty() && newAsn != lastAsnNumber) {
                    if (!AsnDatabase.isTrusted(newAsn) && !userTrusted.contains(newAsn)) {
                        triggerAsnChangeOverlay(newAsn, newOrg.toString(), lang)
                    }
                }
                
                lastAsnNumber = newAsn
                lastAsnInfo = newOrg.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASN Fetch Error: ${e.message}")
        }
    }

    private fun startMonitoring() {
        serviceScope.launch {
            val lang = settingsDataStore.selectedLanguage.first()
            fetchAsnData(lang)
            updateNetworkData(lang)
            registerNetworkCallbacks()
            
            while (isActive) {
                try {
                    // Acquire WakeLock for scan cycle only, auto-release after 30s
                    wakeLock?.acquire(30_000L)

                    val currentLang = settingsDataStore.selectedLanguage.first()
                    
                    if (lastAsnInfo == "Loading..." || lastAsnInfo == "Unknown") {
                        fetchAsnData(currentLang)
                    }

                    val threatLevel = updateNetworkData(currentLang)
                    
                    if (threatLevel < 100) {
                        lastAlertedCellId = ""
                    }

                    // updateNetworkData now returns effective threat (max of scan + forensic)
                    NetworkStateTracker.updateThreatLevel(threatLevel)
                    updateNotification(threatLevel, currentLang)

                    // Kill Switch: trigger only if enabled in settings
                    if (threatLevel >= 76) {
                        val killSwitchEnabled = settingsDataStore.killSwitchEnabled.first()
                        if (killSwitchEnabled) {
                            if (!com.android.imeisettings.util.KillSwitchController.isRadioDisabled.value) {
                                Log.w(TAG, "Kill Switch activated due to threat level $threatLevel")
                                com.android.imeisettings.util.KillSwitchController.triggerKillSwitch(this@NetworkSecurityService)
                                db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "KILL_SWITCH", "Kill Switch activated: threat=$threatLevel"))
                            }
                        }
                    } else if (threatLevel < 25) {
                        if (com.android.imeisettings.util.KillSwitchController.isRadioDisabled.value) {
                            com.android.imeisettings.util.KillSwitchController.restoreRadio(this@NetworkSecurityService)
                            db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "KILL_SWITCH", "Radio restored: threat level dropped to $threatLevel"))
                        }
                    }

                    // Auto-Clean Logs: periodically remove old logs if enabled
                    val now = System.currentTimeMillis()
                    if (now - lastCleanupTime > 3600_000L) {
                        lastCleanupTime = now
                        val autoClean = settingsDataStore.autoCleanLogs.first()
                        if (autoClean) {
                            val cutoff = now - 7 * 24 * 3600_000L
                            db.securityLogDao().deleteLogsBefore(cutoff)
                            Log.d(TAG, "Auto-Clean: removed logs older than 7 days")
                        }
                    }

                    // Radio Log Monitor: check setting dynamically
                    val radioEnabled = settingsDataStore.radioLogMonitorEnabled.first()
                    if (radioEnabled && radioLogMonitor == null) {
                        startRadioLogMonitor()
                    } else if (!radioEnabled && radioLogMonitor != null) {
                        stopRadioLogMonitor()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor Error: ${e.message}")
                } finally {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
                delay(5000)
            }
        }
    }

    private var callbacksRegistered = false

    private fun registerNetworkCallbacks() {
        if (callbacksRegistered) return
        callbacksRegistered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener, TelephonyCallback.SignalStrengthsListener, TelephonyCallback.CallStateListener {
                override fun onServiceStateChanged(serviceState: ServiceState) {
                    serviceScope.launch { 
                        val lang = settingsDataStore.selectedLanguage.first()
                        updateNetworkData(lang)
                        handleNetworkChangeRotation()
                    }
                }
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    serviceScope.launch { 
                        val lang = settingsDataStore.selectedLanguage.first()
                        updateNetworkData(lang) 
                    }
                }
                override fun onCallStateChanged(state: Int) {
                    isCallActive = state != TelephonyManager.CALL_STATE_IDLE
                    serviceScope.launch { 
                        val lang = settingsDataStore.selectedLanguage.first()
                        updateNetworkData(lang) 
                    }
                }
            }
            registeredTelephonyCallback = callback
            telephonyManager.registerTelephonyCallback(Executors.newSingleThreadExecutor(), callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onServiceStateChanged(serviceState: ServiceState?) {
                    serviceScope.launch { 
                        val lang = settingsDataStore.selectedLanguage.first()
                        updateNetworkData(lang)
                        handleNetworkChangeRotation()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    serviceScope.launch { 
                        val lang = settingsDataStore.selectedLanguage.first()
                        updateNetworkData(lang) 
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    isCallActive = state != TelephonyManager.CALL_STATE_IDLE
                    serviceScope.launch { 
                        val lang = settingsDataStore.selectedLanguage.first()
                        updateNetworkData(lang) 
                    }
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun updateNetworkData(lang: String): Int {
        val activeSubs = try { subscriptionManager.activeSubscriptionInfoList ?: emptyList() } catch (e: Exception) { emptyList() }
        val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isCellularData = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        var maxThreat = 0
        var infoChanged = false
        var anySimPresent = false
        val r = reasonsTrans[lang] ?: reasonsTrans["en"]!!
        val logEntries = mutableListOf<String>()

        for (slotIdx in 0..1) {
            val simState = telephonyManager.getSimState(slotIdx)
            val isPresent = simState != TelephonyManager.SIM_STATE_ABSENT && simState != TelephonyManager.SIM_STATE_UNKNOWN
            val sub = activeSubs.find { it.simSlotIndex == slotIdx }
            
            if (!isPresent || sub == null) {
                val empty = NetworkDetails(voiceState = "NO SIM", signalDbm = -140, operator = r["no_sim"] ?: "NO SIM", networkType = "N/A")
                if (slotIdx == 0) NetworkStateTracker.updateSim1(empty) else NetworkStateTracker.updateSim2(empty)
                logEntries.add("SIM ${slotIdx + 1}: ${r["no_sim"]}")
                continue
            }

            anySimPresent = true
            val subTm = telephonyManager.createForSubscriptionId(sub.subscriptionId)
            val serviceState = subTm.serviceState
            val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subTm.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm ?: -140
            } else {
                @Suppress("DEPRECATION")
                subTm.signalStrength?.gsmSignalStrength ?: -140
            }

            var cellId = "---"; var lacTac = "---"; var pciPsc = "---"; var arfcn = "---"; var networkType = "N/A"
            var band = "Auto"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && serviceState != null) {
                val regInfo = serviceState.networkRegistrationInfoList.find { 
                    @Suppress("DEPRECATION")
                    val reg = it.isRegistered
                    reg && (it.domain == NetworkRegistrationInfo.DOMAIN_PS || it.domain == NetworkRegistrationInfo.DOMAIN_CS)
                }
                regInfo?.cellIdentity?.let { id ->
                    val cid = getCellIdStr(id)
                    if (cid != "---") {
                        cellId = cid; lacTac = getLacTacStr(id); pciPsc = getPciStr(id); arfcn = getArfcnStr(id); networkType = getNetTypeStr(id)
                        band = getBandStr(id)
                    }
                }
            }

            if (cellId == "---") {
                val allCells = try { subTm.allCellInfo ?: emptyList() } catch (e: Exception) { emptyList() }
                val subMcc = getSubMcc(sub)
                val subMnc = getSubMnc(sub)
                val registeredCell = allCells.find { cell ->
                    val isCellRegistered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        cell.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING || cell.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING
                    } else {
                        @Suppress("DEPRECATION")
                        cell.isRegistered
                    }
                    if (!isCellRegistered) return@find false
                    val id = getCellIdentitySafe(cell) ?: return@find false
                    val cellMcc = getMcc(id); val cellMnc = getMnc(id)
                    (cellMcc == null || cellMcc == subMcc) && (cellMnc == null || cellMnc == subMnc)
                }
                if (registeredCell != null) {
                    val id = getCellIdentitySafe(registeredCell)
                    if (id != null) {
                        cellId = getCellIdStr(id); lacTac = getLacTacStr(id); pciPsc = getPciStr(id); arfcn = getArfcnStr(id); networkType = getNetTypeStr(id)
                        band = getBandStr(id)
                    }
                }
            }
            
            if (slotIdx == 0 && cellId != "---" && cellId != lastCellIdSim1) { lastCellIdSim1 = cellId; infoChanged = true; checkCellFingerprint(cellId, lacTac, arfcn, dbm, lang) }
            if (slotIdx == 1 && cellId != "---" && cellId != lastCellIdSim2) { lastCellIdSim2 = cellId; infoChanged = true; checkCellFingerprint(cellId, lacTac, arfcn, dbm, lang) }

            val neighborsCount = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    subTm.requestCellInfoUpdate(Executors.newSingleThreadExecutor(), object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {}
                    })
                }
                val allCells = try { subTm.allCellInfo ?: emptyList() } catch (e: Exception) { emptyList() }
                val cellsToCount = if (allCells.isEmpty()) telephonyManager.allCellInfo ?: emptyList() else allCells
                cellsToCount.count { cell ->
                    val isRegistered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cell.cellConnectionStatus != CellInfo.CONNECTION_NONE else {
                        @Suppress("DEPRECATION")
                        cell.isRegistered
                    }
                    !isRegistered && getDbmSafe(cell) > -140
                }
            } catch (e: Exception) { 0 }

            val encryption = getRealEncryptionType(subTm, networkType, neighborsCount, dbm, isCallActive)
            val isProvidingData = isCellularData && sub.subscriptionId == defaultDataSubId
            val threatReasons = mutableListOf<String>()
            var threat = 0
            var factorCount = 0
            
            // === Weight system (max 100%) ===
            // ASN mismatch = 50%, No neighbors = 25%, everything else = 5-10%
            
            if (networkType == "GSM") { threat += 10; factorCount++; threatReasons.add(r["t_gsm"] ?: "2G") }
            if (neighborsCount == 0 && cellId != "---" && !networkType.contains("searching", ignoreCase = true)) {
                if (isCallActive) {
                    threat += 5; factorCount++
                    threatReasons.add(r["neighbors_0_weak_log"] ?: "No neighbors")
                } else {
                    threat += 25; factorCount++
                    threatReasons.add(r["neighbors_0_weak_log"] ?: "No neighbors")
                }
            }
            if (encryption.contains("NONE") || encryption.contains("EEA0") || encryption.contains("A5/0")) {
                threat += 10; factorCount++
                threatReasons.add(r["t_cipher"] ?: "Cipher")
            }

            // === Enhanced FBS Detection ===
            val fbsResult = performEnhancedFbsDetection(
                subTm, cellId, lacTac, arfcn, dbm, networkType, neighborsCount, isCallActive
            )
            if (fbsResult.additionalThreat > 0) {
                threat += fbsResult.additionalThreat
                factorCount += fbsResult.factorCount
                threatReasons.addAll(fbsResult.reasons)
            }
            
            if (cellId != "---" && lacTac != "---") {
                val currentMccMnc = "${getSubMcc(sub)}${getSubMnc(sub)}"
                for (zone in inMemorySafeZones) {
                    if (zone.mccMnc == currentMccMnc && zone.lacTac == lacTac) {
                        if (!zone.trustedCellIds.split(",").contains(cellId)) {
                            threat = 100; factorCount++; threatReasons.add(r["t_geo"] ?: "Geo")
                            val msg = "Critical: Unknown Tower $cellId in Safe Area ${zone.name}!"
                            if (cellId != lastAlertedCellId) {
                                lastAlertedCellId = cellId
                                serviceScope.launch { db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "GEO_FENCE", msg)) }
                                triggerEmergencyOverlay("GEO_FENCE", msg, lang, cellId, zone.id)
                            }
                        }
                    }
                }
            }

            val userTrusted = settingsDataStore.userTrustedAsns.first()
            if (isProvidingData && lastAsnNumber.isNotEmpty() && lastAsnInfo != "Loading..." &&
                !AsnDatabase.isAsnValidForSim("${sub.mccString} / ${sub.mncString}", lastAsnNumber, lastAsnInfo) &&
                !userTrusted.contains(lastAsnNumber)) {
                threat += 50; factorCount++; threatReasons.add(r["t_asn"] ?: "ASN")
            }
            
            // Learning Mode Logic
            val isLearningActive = settingsDataStore.learningModeActive.first()
            val learningEndTime = settingsDataStore.learningModeEndTime.first()
            if (isLearningActive) {
                if (System.currentTimeMillis() > learningEndTime) {
                    settingsDataStore.setLearningMode(false)
                    db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "INFO", "Learning Mode completed automatically."))
                } else if (cellId != "---" && lacTac != "---") {
                    val currentMccMnc = "${getSubMcc(sub)}${getSubMnc(sub)}"
                    serviceScope.launch {
                        val existing = db.safeZoneDao().getSafeZoneByLocation(currentMccMnc, lacTac)
                        if (existing != null) {
                            val ids = existing.trustedCellIds.split(",").toMutableSet()
                            if (!ids.contains(cellId)) {
                                ids.add(cellId)
                                db.safeZoneDao().insertSafeZone(existing.copy(trustedCellIds = ids.joinToString(",")))
                                db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "LEARNING", "Whitelisted new tower $cellId in ${existing.name}"))
                            }
                        } else {
                            val newZone = com.android.imeisettings.data.local.SafeZone(
                                name = "Learned Zone ($lacTac)",
                                mccMnc = currentMccMnc,
                                lacTac = lacTac,
                                trustedCellIds = cellId
                            )
                            db.safeZoneDao().insertSafeZone(newZone)
                            db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "LEARNING", "Created new Learned Zone for tower $cellId"))
                        }
                    }
                }
            }
            
            val details = NetworkDetails(
                operator = if (serviceState?.state == ServiceState.STATE_IN_SERVICE) sub.carrierName.toString() else r["state_searching"] ?: "Searching...",
                mccMnc = "${sub.mccString} / ${sub.mncString}", signalDbm = dbm,
                voiceState = if (serviceState?.state == ServiceState.STATE_IN_SERVICE) "state_in_service" else "state_searching",
                cellId = cellId, lacTac = lacTac, pciPsc = pciPsc, arfcn = arfcn, networkType = networkType,
                encryption = encryption, neighbors = neighborsCount,
                asnInfo = if (isProvidingData) lastAsnInfo else "---", asnNumber = if (isProvidingData) lastAsnNumber else "",
                roaming = if (serviceState?.roaming == true) "state_on" else "state_off", band = band
            )
            val cappedThreat = threat.coerceAtMost(100)

            // Build readable multi-line log entry
            val simLine = StringBuilder()
            simLine.append("SIM ${slotIdx + 1}: ${details.operator}")
            simLine.append("\n  CID: ${details.cellId}  ·  ${details.networkType}")
            if (details.lacTac != "---") simLine.append("  ·  LAC: ${details.lacTac}")
            simLine.append("\n  ${r["log_threat"] ?: "Threat"}: ${cappedThreat}%")
            if (threatReasons.isNotEmpty()) {
                // Format reasons as readable list (strip "FBS: " prefix for cleaner display)
                val formattedReasons = threatReasons.map { reason ->
                    reason.removePrefix("FBS: ")
                }
                simLine.append("\n  ⚡ ${formattedReasons.joinToString("\n  ⚡ ")}")
            }
            logEntries.add(simLine.toString())
            maxThreat = maxOf(maxThreat, cappedThreat)
            if (slotIdx == 0) {
                NetworkStateTracker.updateSim1(details)
                lastScanCellId = cellId; lastScanLacTac = lacTac; lastScanDbm = dbm
                lastScanNetworkType = networkType; lastScanEncryption = encryption; lastScanNeighbors = neighborsCount
            } else {
                NetworkStateTracker.updateSim2(details)
            }
        }

        // Also consider forensic threat level for effective display
        val forensicLevel = if (NetworkStateTracker.forensicThreatActive) NetworkStateTracker.totalThreatLevel.value else 0
        val effectiveThreat = maxOf(maxThreat, forensicLevel)

        val logDetails = logEntries.joinToString("\n\n")
        if (effectiveThreat != lastThreatLevel || infoChanged || System.currentTimeMillis() - lastLogTime > 30000) {
            serviceScope.launch { db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "MONITOR", logDetails)) }
            // Alert triggers when: effective threat >= 76% AND this is a new alert (wasn't >= 76 before)
            // Cooldown: at least 60 seconds between alerts to avoid spam
            val now = System.currentTimeMillis()
            if (effectiveThreat >= 76 && (lastThreatLevel < 76 || now - lastAlertTime > 60_000L)) {
                lastAlertTime = now
                val reason = logDetails
                triggerEmergencyOverlay(
                    r["sec_threat"] ?: "SECURITY THREAT", reason, lang,
                    cellId = lastScanCellId, threatLevel = effectiveThreat, rssi = lastScanDbm,
                    lacTac = lastScanLacTac, networkType = lastScanNetworkType,
                    encryption = lastScanEncryption, neighbors = lastScanNeighbors
                )
            }
            // Send BLE alert to connected wearables when threat changes
            if (effectiveThreat != lastThreatLevel && effectiveThreat > 0 && WearAlertService.isActive()) {
                try {
                    val shortReason = logDetails.lines()
                        .filter { it.trimStart().startsWith("\u26a1") }
                        .joinToString(", ") { it.trim().removePrefix("\u26a1 ") }
                        .ifEmpty { "Threat: $effectiveThreat%" }
                    WearAlertService.sendAlert(effectiveThreat, shortReason)
                } catch (_: Exception) {}
            }
            lastThreatLevel = effectiveThreat; lastLogTime = System.currentTimeMillis()
        }
        // Reset forensic threat if scan-computed threat is below threshold for 3 consecutive scans
        if (NetworkStateTracker.forensicThreatActive && maxThreat < 50) {
            forensicLowCount++
            if (forensicLowCount >= 3) {
                NetworkStateTracker.resetThreatLevel()
                forensicLowCount = 0
            }
        } else {
            forensicLowCount = 0
        }
        return if (anySimPresent) effectiveThreat else -1
    }

    // ==================== ENHANCED FBS DETECTION ====================

    data class FbsDetectionResult(val additionalThreat: Int, val reasons: List<String>, val factorCount: Int = 0)

    // Cell tower history: tracks recently seen cell IDs with their LAC and signal levels
    private val cellTowerHistory = java.util.concurrent.ConcurrentHashMap<String, CellHistoryEntry>()
    data class CellHistoryEntry(
        val cellId: String, val lacTac: String, val arfcn: String,
        val firstSeen: Long, val lastSeen: Long, val dbmHistory: java.util.concurrent.CopyOnWriteArrayList<Int>
    )

    // === Signal Strength Statistical Tracker (AIMSICD-style) ===
    // Maintains running average signal strength per CID, flags outliers ≥ MYSTERIOUS_DIFF dBm
    private val signalStrengthAverages = java.util.concurrent.ConcurrentHashMap<Int, SignalStats>()
    private var lastSignalRegTime = 0L
    private val signalRegIntervalMs = 60_000L // Register signal sample every 60s per AIMSICD
    data class SignalStats(val cidKey: Int, val totalDbm: Long, val sampleCount: Int, val lastUpdated: Long) {
        val average: Int get() = if (sampleCount > 0) (totalDbm / sampleCount).toInt() else -100
    }

    private fun checkSignalStrengthAnomaly(cidNum: Int, dbm: Int): Pair<Int, String?> {
        val now = System.currentTimeMillis()
        if (now - lastSignalRegTime < signalRegIntervalMs) return Pair(0, null)
        lastSignalRegTime = now

        val mysteriousDiff = 10 // dBm threshold from AIMSICD SignalStrengthTracker

        val existing = signalStrengthAverages[cidNum]
        if (existing != null && existing.sampleCount >= 3) {
            val diff = Math.abs(dbm - existing.average)
            if (diff >= mysteriousDiff) {
                val msg = "Signal anomaly CID=$cidNum: current=${dbm}dBm, avg=${existing.average}dBm, diff=${diff}dB"
                Log.w(TAG, msg)
                signalStrengthAverages[cidNum] = existing.copy(
                    totalDbm = existing.totalDbm + dbm, sampleCount = existing.sampleCount + 1, lastUpdated = now
                )
                return Pair(minOf(diff * 2, 40), msg)
            }
        }

        // Register this sample
        if (existing != null) {
            signalStrengthAverages[cidNum] = existing.copy(
                totalDbm = existing.totalDbm + dbm, sampleCount = existing.sampleCount + 1, lastUpdated = now
            )
        } else {
            signalStrengthAverages[cidNum] = SignalStats(cidNum, dbm.toLong(), 1, now)
        }

        // Cleanup entries older than 2 hours
        signalStrengthAverages.entries.removeAll { now - it.value.lastUpdated > 7_200_000 }
        return Pair(0, null)
    }

    @SuppressLint("MissingPermission")
    private fun performEnhancedFbsDetection(
        tm: TelephonyManager, cellId: String, lacTac: String, arfcn: String,
        dbm: Int, networkType: String, neighborsCount: Int, isCallActive: Boolean
    ): FbsDetectionResult {
        if (cellId == "---" || cellId.isEmpty()) return FbsDetectionResult(0, emptyList(), 0)

        var additionalThreat = 0
        var factors = 0
        val reasons = mutableListOf<String>()

        try {
            // 1. Timing Advance anomaly (GSM/LTE)
            // TA=0..1 means tower within 78m (LTE) or 550m (GSM).
            // This is NORMAL in urban areas — only add 5% as a minor indicator,
            // and ONLY when TA=0 with very strong signal (truly suspicious).
            val allCells = try { tm.allCellInfo ?: emptyList() } catch (_: Exception) { emptyList() }
            for (cell in allCells) {
                val isServing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    cell.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING
                else @Suppress("DEPRECATION") cell.isRegistered

                if (!isServing) continue

                when (cell) {
                    is CellInfoLte -> {
                        val ta = cell.cellSignalStrength.timingAdvance
                        if (ta != Int.MAX_VALUE && ta == 0 && dbm > -50) {
                            additionalThreat += 5; factors++
                            reasons.add("Timing Advance=$ta (tower <78m)")
                        }
                    }
                    is CellInfoGsm -> {
                        val ta = cell.cellSignalStrength.timingAdvance
                        if (ta != Int.MAX_VALUE && ta == 0 && dbm > -50) {
                            additionalThreat += 5; factors++
                            reasons.add("TA=$ta GSM (tower <550m)")
                        }
                    }
                }
            }

            // 2. LAC/TAC anomaly — sudden LAC change without location change (10%)
            val history = cellTowerHistory[cellId]
            if (history != null && history.lacTac != lacTac) {
                additionalThreat += 10; factors++
                reasons.add("LAC changed ${history.lacTac}->$lacTac for same CID")
                serviceScope.launch {
                    db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "FBS_LAC",
                        "LAC anomaly: CID $cellId changed LAC from ${history.lacTac} to $lacTac"))
                }
            }

            // 3. ARFCN anomaly — frequency changed for same cell (5%)
            if (history != null && history.arfcn != "---" && arfcn != "---" && history.arfcn != arfcn) {
                additionalThreat += 5; factors++
                reasons.add("ARFCN changed ${history.arfcn}->$arfcn for CID $cellId")
            }

            // 4. Signal strength anomaly — sudden large jump (>20dBm) in signal (10%)
            if (history != null && history.dbmHistory.isNotEmpty()) {
                val avgDbm = history.dbmHistory.takeLast(5).average()
                val jump = dbm - avgDbm
                if (jump > 20 && dbm > -60) {
                    additionalThreat += 10; factors++
                    reasons.add("Signal jump +${jump.toInt()}dBm (avg=${avgDbm.toInt()}, now=$dbm)")
                }
            }

            // 5. CID too low — portable IMSI catchers often use very low CIDs (5%)
            val cidNum = cellId.toLongOrNull()
            if (cidNum != null && cidNum in 1..10 && !isCallActive) {
                additionalThreat += 5; factors++
                reasons.add("Suspicious low CID=$cellId")
            }

            // 6. Neighbor cell signal analysis — all neighbors weaker by >30dB = isolation attack (10%)
            if (neighborsCount > 0 && !isCallActive) {
                val neighborDbms = allCells
                    .filter { cell ->
                        val reg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            cell.cellConnectionStatus == CellInfo.CONNECTION_NONE
                        else @Suppress("DEPRECATION") !cell.isRegistered
                        reg && getDbmSafe(cell) > -140
                    }
                    .map { getDbmSafe(it) }

                if (neighborDbms.isNotEmpty()) {
                    val maxNeighborDbm = neighborDbms.max()
                    val gap = dbm - maxNeighborDbm
                    if (gap > 30 && dbm > -60) {
                        additionalThreat += 10; factors++
                        reasons.add("Signal isolation gap=${gap}dB (serving=$dbm, best neighbor=$maxNeighborDbm)")
                    }
                }
            }

            // 7. Network downgrade detection — was on LTE/5G, suddenly on GSM (10%)
            if (history != null && networkType == "GSM") {
                val prevEntry = cellTowerHistory.values
                    .filter { it.lastSeen > System.currentTimeMillis() - 60_000 }
                    .maxByOrNull { it.lastSeen }
                if (prevEntry != null && prevEntry.cellId != cellId) {
                    additionalThreat += 10; factors++
                    reasons.add("Downgrade to 2G detected")
                }
            }

            // 8. Signal Strength Statistical Anomaly — AIMSICD-style (5% max)
            val cidInt = cellId.toIntOrNull()
            if (cidInt != null && dbm > -140 && !isCallActive) {
                val (sigThreat, sigMsg) = checkSignalStrengthAnomaly(cidInt, dbm)
                if (sigThreat > 0 && sigMsg != null) {
                    additionalThreat += minOf(sigThreat, 5); factors++
                    reasons.add(sigMsg)
                }
            }

            // 9. 5G NR cell analysis — check for NSA anchor anomalies (5%)
            for (cell in allCells) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is android.telephony.CellInfoNr) {
                    val nrIdentity = cell.cellIdentity as? android.telephony.CellIdentityNr
                    val nrSs = cell.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                    if (nrIdentity != null && nrSs != null) {
                        val nrPci = nrIdentity.pci
                        if (nrPci in 0..2) {
                            additionalThreat += 5; factors++
                            reasons.add("Suspicious 5G NR PCI=$nrPci (test range)")
                        }
                        val ssRsrp = nrSs.ssRsrp
                        if (ssRsrp > -60 && neighborsCount == 0) {
                            additionalThreat += 10; factors++
                            reasons.add("Isolated 5G NR cell RSRP=${ssRsrp}dBm, no neighbors")
                        }
                    }
                }
            }

            // 10. Multi-RAT consistency check (5%)
            if (networkType == "5G") {
                val hasNrCell = allCells.any { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it is android.telephony.CellInfoNr }
                if (!hasNrCell && dbm > -80) {
                    additionalThreat += 5; factors++
                    reasons.add("5G indicated but no NR cell info (possible spoofed)")
                }
            }

            // 11. Cell tower lifetime check — newly appeared tower with very strong signal (5%)
            if (history == null && dbm > -55 && !isCallActive) {
                additionalThreat += 5; factors++
                reasons.add("New tower CID=$cellId with strong signal ${dbm}dBm")
            }

            // 12. RRC redirect detection — rapid cell changes within short time (10%)
            val recentChanges = cellTowerHistory.values.count {
                System.currentTimeMillis() - it.lastSeen < 30_000 && it.cellId != cellId
            }
            if (recentChanges >= 3) {
                additionalThreat += 10; factors++
                reasons.add("Rapid cell switching ($recentChanges changes in 30s)")
            }

            // 13. Extended TA analysis for NR (5G) — extremely strong signal (10%)
            for (cell in allCells) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is android.telephony.CellInfoNr) {
                    val isServing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        cell.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING
                    else true
                    if (!isServing) continue

                    val nrSs = cell.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                    if (nrSs != null) {
                        val ssRsrp = nrSs.ssRsrp
                        if (ssRsrp > -50 && neighborsCount <= 1 && !isCallActive) {
                            additionalThreat += 10; factors++
                            reasons.add("Extremely strong 5G NR signal ${ssRsrp}dBm with ${neighborsCount} neighbors")
                        }
                    }
                }
            }

            // 14. PLMN consistency check — serving cell MCC/MNC should match SIM (10%)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                for (cell in allCells) {
                    val isServing = cell.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING
                    if (!isServing) continue
                    val identity = getCellIdentitySafe(cell) ?: continue
                    val cellMcc = getMcc(identity)
                    val cellMnc = getMnc(identity)
                    if (cellMcc != null && cellMnc != null) {
                        val simMcc = tm.simOperator?.take(3)
                        val simMnc = tm.simOperator?.drop(3)
                        if (simMcc != null && simMnc != null && 
                            simMcc.isNotEmpty() && simMnc.isNotEmpty() &&
                            (cellMcc != simMcc || cellMnc != simMnc) &&
                            tm.serviceState?.roaming != true) {
                            additionalThreat += 10; factors++
                            reasons.add("PLMN mismatch — cell=$cellMcc/$cellMnc, SIM=$simMcc/$simMnc")
                        }
                    }
                }
            }

            // 15. Cell tower broadcast timing anomaly (5%)
            if (history != null && history.firstSeen > 0) {
                val towerAge = System.currentTimeMillis() - history.firstSeen
                if (towerAge < 300_000 && dbm > -55 && !isCallActive) {
                    additionalThreat += 5; factors++
                    reasons.add("Recently appeared tower (${towerAge / 1000}s ago) with strong signal ${dbm}dBm")
                }
            }

            // 16. Encryption downgrade detection via cipher indicator (5%)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceState = tm.serviceState
                if (serviceState != null) {
                    val nrState = try {
                        serviceState.javaClass.getMethod("getNrState").invoke(serviceState)
                    } catch (_: Exception) { null }
                    if (networkType == "5G" && nrState?.toString() == "NONE") {
                        additionalThreat += 5; factors++
                        reasons.add("5G icon showing but NR state is NONE (possible NR spoofing)")
                    }
                }
            }

            // 17. RILDefender rapid cell switch integration
            RilDefenderEngine.recordCellSwitch()

            // Update cell tower history
            val now = System.currentTimeMillis()
            val existing = cellTowerHistory[cellId]
            if (existing != null) {
                existing.dbmHistory.add(dbm)
                if (existing.dbmHistory.size > 20) existing.dbmHistory.removeAt(0)
                cellTowerHistory[cellId] = existing.copy(lastSeen = now, lacTac = lacTac, arfcn = arfcn)
            } else {
                cellTowerHistory[cellId] = CellHistoryEntry(
                    cellId = cellId, lacTac = lacTac, arfcn = arfcn,
                    firstSeen = now, lastSeen = now, dbmHistory = java.util.concurrent.CopyOnWriteArrayList(listOf(dbm))
                )
            }

            // Cleanup old entries (>1 hour)
            cellTowerHistory.keys.removeAll { key ->
                val entry = cellTowerHistory[key]
                entry != null && now - entry.lastSeen > 3600_000
            }

        } catch (e: Exception) {
            Log.e(TAG, "Enhanced FBS detection error: ${e.message}")
        }

        return FbsDetectionResult(additionalThreat, reasons, factors)
    }

    // ==================== END ENHANCED FBS DETECTION ====================

    private fun getRealEncryptionType(tm: TelephonyManager, networkType: String, neighbors: Int, dbm: Int, callActive: Boolean): String {
        // Show expected encryption for the technology.
        // Actual cipher indicators come from radio logs, not from neighbor heuristics.
        return when (networkType) {
            "5G" -> "5G: NEA2 (AES)"
            "LTE" -> "LTE: EEA2 / EEA1"
            "3G" -> "3G: UEA2 (AES-128)"
            "GSM" -> "2G: A5/3 (STRONG)"
            else -> "N/A"
        }
    }

    private fun getDbmSafe(cell: CellInfo): Int {
        val dbm = when (cell) {
            is CellInfoLte -> cell.cellSignalStrength.dbm
            is CellInfoGsm -> cell.cellSignalStrength.dbm
            is CellInfoWcdma -> cell.cellSignalStrength.dbm
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) cell.cellSignalStrength.dbm
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cell.cellSignalStrength.dbm else -140
        }
        
        var finalDbm = dbm
        // Android bug/behavior: sometimes GSM strength is reported as ASU (0..31) instead of dBm
        if (finalDbm in 0..31) {
            finalDbm = -113 + (2 * finalDbm)
        }
        
        return if (finalDbm == 0 || finalDbm == Int.MAX_VALUE) -140 else finalDbm
    }

    private fun getCellIdStr(id: CellIdentity): String = when(id) {
        is CellIdentityNr -> id.nci.takeIf { it != Long.MAX_VALUE }?.toString() ?: "---"
        is CellIdentityLte -> id.ci.takeIf { it != Int.MAX_VALUE }?.toString() ?: "---"
        is CellIdentityGsm -> id.cid.takeIf { it != Int.MAX_VALUE }?.toString() ?: "---"
        is CellIdentityWcdma -> id.cid.takeIf { it != Int.MAX_VALUE }?.toString() ?: "---"
        else -> "---"
    }

    private fun getLacTacStr(id: CellIdentity): String = when(id) {
        is CellIdentityNr -> id.tac.takeIf { it != Int.MAX_VALUE }?.toString() ?: "---"
        is CellIdentityLte -> id.tac.takeIf { it != Int.MAX_VALUE }?.toString() ?: "---"
        is CellIdentityGsm -> id.lac.takeIf { it != Int.MAX_VALUE }?.toString() ?: "---"
        is CellIdentityWcdma -> id.lac.takeIf { it != Int.MAX_VALUE }?.toString() ?: "---"
        else -> "---"
    }

    private fun getPciStr(id: CellIdentity): String = when(id) {
        is CellIdentityNr -> id.pci.toString(); is CellIdentityLte -> id.pci.toString(); else -> "---"
    }.replace(Int.MAX_VALUE.toString(), "---")

    private fun getArfcnStr(id: CellIdentity): String = when(id) {
        is CellIdentityNr -> id.nrarfcn.toString(); is CellIdentityLte -> id.earfcn.toString()
        is CellIdentityGsm -> id.arfcn.toString(); is CellIdentityWcdma -> id.uarfcn.toString(); else -> "---"
    }.replace(Int.MAX_VALUE.toString(), "---")

    private fun getNetTypeStr(id: CellIdentity): String = when(id) {
        is CellIdentityNr -> "5G"; is CellIdentityLte -> "LTE"; is CellIdentityGsm -> "GSM"; is CellIdentityWcdma -> "3G"; else -> "N/A"
    }

    private fun getBandStr(id: CellIdentity): String = when(id) {
        is CellIdentityLte -> {
            val earfcn = id.earfcn
            when {
                earfcn in 0..599 -> "B1 (2100)"; earfcn in 1200..1949 -> "B3 (1800)"; earfcn in 2400..2649 -> "B5 (850)"
                earfcn in 2750..3449 -> "B7 (2600)"; earfcn in 3450..3799 -> "B8 (900)"; earfcn in 6150..6449 -> "B20 (800)"
                earfcn in 37750..38249 -> "B38 (2600)"; earfcn in 38650..39649 -> "B40 (2300)"; else -> "LTE"
            }
        }
        is CellIdentityGsm -> "GSM 900/1800"; is CellIdentityWcdma -> "B1 (2100)"; is CellIdentityNr -> "5G NR"; else -> "Auto"
    }

    private fun getMcc(id: CellIdentity): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        when (id) {
            is CellIdentityLte -> id.mccString; is CellIdentityGsm -> id.mccString; is CellIdentityWcdma -> id.mccString
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && id is CellIdentityNr) id.mccString else null
        }
    } else {
        @Suppress("DEPRECATION")
        when (id) {
            is CellIdentityLte -> id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
            is CellIdentityGsm -> id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
            is CellIdentityWcdma -> id.mcc.takeIf { it != Int.MAX_VALUE }?.toString(); else -> null
        }
    }

    private fun getMnc(id: CellIdentity): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        when (id) {
            is CellIdentityLte -> id.mncString; is CellIdentityGsm -> id.mncString; is CellIdentityWcdma -> id.mncString
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && id is CellIdentityNr) id.mncString else null
        }
    } else {
        @Suppress("DEPRECATION")
        when (id) {
            is CellIdentityLte -> id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
            is CellIdentityGsm -> id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
            is CellIdentityWcdma -> id.mnc.takeIf { it != Int.MAX_VALUE }?.toString(); else -> null
        }
    }

    private fun getSubMcc(sub: SubscriptionInfo): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sub.mccString else {
        @Suppress("DEPRECATION")
        sub.mcc.takeIf { it != 0 && it != Int.MAX_VALUE }?.toString()
    }
    private fun getSubMnc(sub: SubscriptionInfo): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sub.mncString else {
        @Suppress("DEPRECATION")
        sub.mnc.takeIf { it != 0 && it != Int.MAX_VALUE }?.toString()
    }

    private fun getCellIdentitySafe(cell: CellInfo): CellIdentity? = when (cell) {
        is CellInfoLte -> cell.cellIdentity; is CellInfoGsm -> cell.cellIdentity; is CellInfoWcdma -> cell.cellIdentity
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) cell.cellIdentity else null
    }

    private fun updateNotification(threatLevel: Int, lang: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val r = reasonsTrans[lang] ?: reasonsTrans["en"]!!
        val customReason = NetworkStateTracker.threatReason.value
        val content = when {
            threatLevel < 0 -> r["no_sim"]!!
            customReason != null -> customReason
            threatLevel >= 100 -> r["cipher_off"]!!
            threatLevel >= 50 -> r["threat_detected"]!!
            threatLevel >= 25 -> r["neighbors_0_weak"] ?: r["neighbors_0_strong"]!!
            else -> r["secure"]!!
        }
        if (content != lastShownContent || getThreatColorCategory(threatLevel) != getThreatColorCategory(lastShownThreatLevel)) {
            nm.notify(NOTIFICATION_ID, createNotification(content, threatLevel))
            lastShownContent = content; lastShownThreatLevel = threatLevel
        }
    }

    private fun getThreatColorCategory(threat: Int): Int = when {
        threat < 0 -> 0; threat >= 50 -> 1; threat >= 25 -> 2; else -> 3
    }

    private fun triggerAsnChangeOverlay(asn: String, org: String, lang: String) {
        val intent = Intent(this, EmergencyOverlayService::class.java).apply {
            putExtra(EmergencyOverlayService.EXTRA_TYPE, EmergencyOverlayService.TYPE_ASN_CHECK)
            putExtra(EmergencyOverlayService.EXTRA_TITLE, "ASN CHANGE DETECTED")
            putExtra(EmergencyOverlayService.EXTRA_REASON, "New ASN: $asn\nProvider: $org")
            putExtra(EmergencyOverlayService.EXTRA_LANG, lang)
        }
        startService(intent)
    }

    private fun triggerEmergencyOverlay(
        type: String, description: String, lang: String,
        cellId: String? = null, zoneId: Int = -1,
        threatLevel: Int = -1, rssi: Int = -140, lacTac: String = "---",
        networkType: String = "N/A", encryption: String = "N/A", neighbors: Int = -1
    ) {
        val intent = Intent(this, EmergencyOverlayService::class.java).apply {
            putExtra(EmergencyOverlayService.EXTRA_TYPE, EmergencyOverlayService.TYPE_EMERGENCY)
            putExtra(EmergencyOverlayService.EXTRA_TITLE, type)
            putExtra(EmergencyOverlayService.EXTRA_REASON, description)
            putExtra(EmergencyOverlayService.EXTRA_LANG, lang)
            if (cellId != null) putExtra(EmergencyOverlayService.EXTRA_CELL_ID, cellId)
            if (zoneId != -1) putExtra(EmergencyOverlayService.EXTRA_ZONE_ID, zoneId)
            putExtra(EmergencyOverlayService.EXTRA_THREAT_LEVEL, threatLevel)
            putExtra(EmergencyOverlayService.EXTRA_RSSI, rssi)
            putExtra(EmergencyOverlayService.EXTRA_LAC_TAC, lacTac)
            putExtra(EmergencyOverlayService.EXTRA_NETWORK_TYPE, networkType)
            putExtra(EmergencyOverlayService.EXTRA_ENCRYPTION, encryption)
            putExtra(EmergencyOverlayService.EXTRA_NEIGHBORS, neighbors)
        }
        startService(intent)
    }

    private fun checkCellFingerprint(cellId: String, lac: String, arfcn: String, dbm: Int, lang: String) {
        serviceScope.launch {
            val dao = db.cellFingerprintDao()
            val existing = dao.getFingerprintById(cellId)
            if (existing != null) {
                var anomaly = false
                val reason = StringBuilder()
                if (existing.arfcn != "---" && arfcn != "---" && existing.arfcn != arfcn) {
                    anomaly = true; reason.append("ARFCN Change (${existing.arfcn} -> $arfcn). ")
                }
                if (dbm > -80 && existing.dbm < -105) {
                    anomaly = true; reason.append("Signal Surge (${existing.dbm} -> $dbm dBm). ")
                }
                if (anomaly) {
                    val msg = "CRITICAL: Tower Profile Anomaly! $cellId | ${reason.toString()}"
                    db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "ALERT", msg))
                    triggerEmergencyOverlay("FINGERPRINT_ALERT", msg, lang)
                    NetworkStateTracker.forceForensicThreat(100, msg)
                }
            }
            dao.insertFingerprint(com.android.imeisettings.data.local.CellFingerprint(cellId, lac, arfcn, dbm, System.currentTimeMillis()))
            dao.deleteOldFingerprints()
        }
    }

    override fun onDestroy() {
        stopRadioLogMonitor()
        try { WearAlertService.stop() } catch (_: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        try { unregisterReceiver(simStateReceiver) } catch (e: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registeredTelephonyCallback?.let {
                try { telephonyManager.unregisterTelephonyCallback(it) } catch (e: Exception) {}
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
