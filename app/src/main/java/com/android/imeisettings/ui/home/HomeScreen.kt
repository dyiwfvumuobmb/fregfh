package com.android.imeisettings.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.ImeiHistory
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.data.repository.NetworkStateTracker
import com.android.imeisettings.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private val transMap = mapOf(
    "en" to listOf(
        "CONSUL IMEI", "Loading...", "NO SIM CARD", "New IMEI", "UPDATE IDENTIFIERS", 
        "English set", "Updating system...", "Update success", "System reboot required to apply changes.", "REBOOT NOW", 
        "Wi-Fi MAC", "BT MAC", "Android ID", "OLD", "NEW", 
        "Update failed", "Changes could not be applied.", "OK", "ERROR DETAILS:", "Processor:", 
        "Method:", "Status:", "LSPosed module is NOT active. PDU and RIL protection is limited.", "READY: All systems operational", "FATAL: Not a system app", 
        "ERROR: Signature denied", "WARNING: API restricted",
        "MTK NVRAM + RadioEx", "QCOM Diag / AIDL", "SPD AT+SPIMEI", "Samsung Exynos RIL", "Google Tensor HAL", "HiSilicon Kirin RIL", "GENERIC SYSTEM"
    ),
    "uk" to listOf(
        "CONSUL IMEI", "Завантаження...", "НЕМАЄ SIM-КАРТКИ", "Новий IMEI", "ОНОВИТИ ІДЕНТИФІКАТОРИ", 
        "Українська встановлена", "Оновлення системи...", "Успішно оновлено", "Потрібне перезавантаження для застосування змін.", "ПЕРЕЗАВАНТАЖИТИ", 
        "Wi-Fi MAC", "BT MAC", "Android ID", "СТАРИЙ", "НОВИЙ", 
        "Помилка оновлення", "Зміни не були застосовані.", "ОК", "ДЕТАЛІ ПОМИЛКИ:", "Процесор:", 
        "Метод:", "Статус:", "Модуль LSPosed НЕ активний. Захист PDU та RIL обмежений.", "ГОТОВО: Всі системи працюють", "КРИТИЧНО: Не системний додаток", 
        "ПОМИЛКА: Підпис відхилено", "УВАГА: API обмежено",
        "MTK NVRAM + RadioEx", "QCOM Diag / AIDL", "SPD AT+SPIMEI", "Samsung Exynos RIL", "Google Tensor HAL", "HiSilicon Kirin RIL", "ЗАГАЛЬНА СИСТЕМА"
    ),
    "ru" to listOf(
        "CONSUL IMEI", "Загрузка...", "НЕТ SIM-КАРТЫ", "Новый IMEI", "ОБНОВИТЬ ИДЕНТИФИКАТОРЫ", 
        "Русский установлен", "Обновление системы...", "Успешно обновлено", "Требуется перезагрузка для применения изменений.", "ПЕРЕЗАГРУЗИТЬ", 
        "Wi-Fi MAC", "BT MAC", "Android ID", "СТАРЫЙ", "НОВЫЙ", 
        "Ошибка обновления", "Изменения не были применены.", "ОК", "ДЕТАЛИ ОШИБКИ:", "Процессор:", 
        "Метод:", "Статус:", "Модуль LSPosed НЕ активен. Защита PDU и RIL ограничена.", "ГОТОВО: Все системы работают", "КРИТИЧЕСКИ: Не системное приложение", 
        "ОШИБКА: Подпись отклонена", "ВНИМАНИЕ: API ограничено",
        "MTK NVRAM + RadioEx", "QCOM Diag / AIDL", "SPD AT+SPIMEI", "Samsung Exynos RIL", "Google Tensor HAL", "HiSilicon Kirin RIL", "ОБЩАЯ СИСТЕМА"
    ),
    "de" to listOf(
        "CONSUL IMEI", "Laden...", "KEINE SIM-KARTE", "Neue IMEI", "IDENTIFIKATOREN AKTUALISIEREN", 
        "Deutsch eingestellt", "System wird aktualisiert...", "Update erfolgreich", "System-Neustart erforderlich.", "JETZT NEUSTARTEN", 
        "Wi-Fi MAC", "BT MAC", "Android ID", "ALT", "NEU", 
        "Update fehlgeschlagen", "Änderungen konnten nicht übernommen werden.", "OK", "FEHLERDETAILS:", "Prozessor:", 
        "Methode:", "Status:", "LSPosed-Modul ist NICHT aktiv. PDU- und RIL-Schutz ist begrenzt.", "BEREIT: Alle Systeme betriebsbereit", "KRITISCH: Keine System-App", 
        "FEHLER: Signatur abgelehnt", "WARNUNG: API eingeschränkt",
        "MTK NVRAM + RadioEx", "QCOM Diag / AIDL", "SPD AT+SPIMEI", "Samsung Exynos RIL", "Google Tensor HAL", "HiSilicon Kirin RIL", "GENERIC SYSTEM"
    ),
    "pl" to listOf(
        "CONSUL IMEI", "Ładowanie...", "BRAK KARTY SIM", "Nowy IMEI", "AKTUALIZUJ IDENTYFIKATORY", 
        "Język polski ustawiony", "Aktualizacja systemu...", "Aktualizacja zakończona sukcesem", "Wymagany restart systemu.", "RESTARTUJ TERAZ", 
        "Wi-Fi MAC", "BT MAC", "Android ID", "STARY", "NOWY", 
        "Aktualizacja nieudana", "Zmiany nie mogły zostać zastosowane.", "OK", "SZCZEGÓŁY BŁĘDU:", "Procesor:", 
        "Metoda:", "Status:", "Moduł LSPosed NIE jest aktywny. Ochrona PDU i RIL jest ograniczona.", "GOTOWE: Wszystkie systemy sprawne", "BŁĄD KRYTYCZNY: Brak uprawnień systemowych", 
        "BŁĄD: Sygnatura odrzucona", "OSTRZEŻENIE: API ograniczone",
        "MTK NVRAM + RadioEx", "QCOM Diag / AIDL", "SPD AT+SPIMEI", "Samsung Exynos RIL", "Google Tensor HAL", "HiSilicon Kirin RIL", "SYSTEM GENERYCZNY"
    ),
    "lt" to listOf(
        "CONSUL IMEI", "Kraunama...", "NĖRA SIM KORTELĖS", "Naujas IMEI", "ATNAUJINTI IDENTIFIKATORIUS", 
        "Lietuvių kalba nustatyta", "Sistemos atnaujinimas...", "Atnaujinta sėkmingai", "Reikalingas sistemos perkrovimas.", "PERKRAUTI DABAR", 
        "Wi-Fi MAC", "BT MAC", "Android ID", "SENAS", "NAUJAS", 
        "Atnaujinimo klaida", "Pakeitimų pritaikyti nepavyko.", "GERAI", "KLAIDOS INFORMACIJA:", "Procesorius:", 
        "Metodas:", "Būsena:", "LSPosed modulis neaktyvus. PDU ir RIL apsauga ribota.", "PARUOŠTA: Visos sistemos veikia", "KRITINĖ KLAIDA: Ne sisteminė programa", 
        "KLAIDA: Parašas atmestas", "ĮSPĖJIMAS: API ribojama",
        "MTK NVRAM + RadioEx", "QCOM Diag / AIDL", "SPD AT+SPIMEI", "Samsung Exynos RIL", "Google Tensor HAL", "HiSilicon Kirin RIL", "BENDRA SISTEMA"
    ),
    "lv" to listOf(
        "CONSUL IMEI", "Ielādē...", "NAV SIM KARTES", "Jauns IMEI", "ATJAUNINĀT IDENTIFIKATORUS", 
        "Latviešu valoda iestatīta", "Sistēma tiek atjaunināta...", "Atjaunināšana veiksmīga", "Nepieciešama sistēmas restartēšana.", "RESTARTĒT TAGAD", 
        "Wi-Fi MAC", "BT MAC", "Android ID", "VECIE", "JAUNIE", 
        "Atjaunināšanas kļūda", "Izmaiņas nevarēja tikt piemērotas.", "LABI", "KĻŪDAS INFORMĀCIJA:", "Procesors:", 
        "Metode:", "Statuss:", "LSPosed modulis nav aktīvs. PDU un RIL aizsardzība ir ierobežota.", "GATAVS: Visas sistēmas darbojas", "KRITISKI: Nav sistēmas lietotne", 
        "KĻŪDA: Paraksts noraidīts", "BRĪDINĀJUMS: API ierobežots",
        "MTK NVRAM + RadioEx", "QCOM Diag / AIDL", "SPD AT+SPIMEI", "Samsung Exynos RIL", "Google Tensor HAL", "HiSilicon Kirin RIL", "VISPĀRĒJĀ SISTĒMA"
    ),
    "es" to listOf(
        "CONSUL IMEI", "Cargando...", "SIN TARJETA SIM", "Nuevo IMEI", "ACTUALIZAR IDENTIFICADORES", 
        "Español establecido", "Actualizando sistema...", "Actualización exitosa", "Se requiere reiniciar el sistema.", "REINICIAR AHORA", 
        "Wi-Fi MAC", "BT MAC", "Android ID", "ANTIGUO", "NUEVO", 
        "Actualización fallida", "No se pudieron aplicar los cambios.", "ACEPTAR", "DETALLE DE ERROR:", "Procesador:", 
        "Método:", "Estado:", "El módulo LSPosed NO está activo. Protección PDU y RIL limitada.", "LISTO: Todos los sistemas operativos", "FATAL: No es una aplicación de sistema", 
        "ERROR: Firma denegada", "ADVERTENCIA: API restringida",
        "MTK NVRAM + RadioEx", "QCOM Diag / AIDL", "SPD AT+SPIMEI", "Samsung Exynos RIL", "Google Tensor HAL", "HiSilicon Kirin RIL", "SISTEMA GENÉRICO"
    )
)

data class ComparisonData(val label: String, val old: String, val new: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsDataStore = remember { SettingsDataStore.getInstance(context) }
    
    val lang by settingsDataStore.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
    val s = transMap[lang] ?: transMap["uk"]!!

    val detailsSim1 by NetworkStateTracker.networkDetailsSim1.collectAsStateWithLifecycle()
    val detailsSim2 by NetworkStateTracker.networkDetailsSim2.collectAsStateWithLifecycle()

    var imei1 by remember { mutableStateOf("---") }
    var imei2 by remember { mutableStateOf("---") }
    var imsi1 by remember { mutableStateOf("---") }
    var imsi2 by remember { mutableStateOf("---") }

    var selectedModel1 by remember { mutableStateOf(ImeiGenerator.models[0]) }
    var newImei1 by remember { mutableStateOf(ImeiGenerator.generateImei(selectedModel1.tac)) }
    var selectedModel2 by remember { mutableStateOf(ImeiGenerator.models[1]) }
    var newImei2 by remember { mutableStateOf(ImeiGenerator.generateImei(selectedModel2.tac)) }

    var showProgress by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("OK") }
    var errorDetails by remember { mutableStateOf("") }
    var comparisonList by remember { mutableStateOf(emptyList<ComparisonData>()) }
    
    var preCheckResult by remember { mutableStateOf<ImeiChangerUtil.PreCheckResult?>(null) }

    fun loadHardwareData() {
        scope.launch {
            val i1 = withContext(Dispatchers.IO) { DeviceIdentifierUtil.getImei(context, 0) }
            val i2 = withContext(Dispatchers.IO) { DeviceIdentifierUtil.getImei(context, 1) }
            val si1 = withContext(Dispatchers.IO) { DeviceIdentifierUtil.getImsi(context, 0) }
            val si2 = withContext(Dispatchers.IO) { DeviceIdentifierUtil.getImsi(context, 1) }
            imei1 = i1; imei2 = i2; imsi1 = si1; imsi2 = si2
            
            val check = withContext(Dispatchers.IO) { ImeiChangerUtil.preCheck(context) }
            preCheckResult = check
        }
    }

    LaunchedEffect(Unit) { loadHardwareData() }
    
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { loadHardwareData() } }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter("android.intent.action.SIM_STATE_CHANGED"), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter("android.intent.action.SIM_STATE_CHANGED"))
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    if (showProgress) {
        SyncProgressDialog(s[6]) 
    }

    if (showResultDialog) {
        if (updateStatus == "OK") {
            UpdateResultDialog(
                title = s[7], msg = s[8], btnText = s[9],
                results = comparisonList, labels = listOf(s[13], s[14]),
                onAction = { showResultDialog = false; scope.launch(Dispatchers.IO) { RootUtil.rebootDevice(context) } }
            )
        } else {
            UpdateErrorDialog(
                title = s[15], msg = s[16], btnText = s[17],
                detailsLabel = s[18], 
                details = ErrorTranslationHelper.getFriendlyError(lang, errorDetails),
                results = comparisonList, labels = listOf(s[13], s[14]),
                onDismiss = { showResultDialog = false }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121216)).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Header (NO Globe Icon)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = com.android.imeisettings.R.drawable.ic_launcher_consul),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = s[0],
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Identifiers Block
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IdentifierRow("IMEI 1", imei1)
            IdentifierRow("IMEI 2", imei2)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))
            IdentifierRow("IMSI 1", if (detailsSim1.voiceState == "NO SIM") s[2] else imsi1)
            IdentifierRow("IMSI 2", if (detailsSim2.voiceState == "NO SIM") s[2] else imsi2)
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Input Fields
        ImeiInputSection("${s[3]} 1", newImei1, { if (it.length <= 15 && it.all { char -> char.isDigit() }) newImei1 = it }, { 
            val m = ImeiGenerator.models[Random.nextInt(ImeiGenerator.models.size)]
            selectedModel1 = m; newImei1 = ImeiGenerator.generateImei(m.tac)
        })
        Spacer(modifier = Modifier.height(16.dp))
        ImeiInputSection("${s[3]} 2", newImei2, { if (it.length <= 15 && it.all { char -> char.isDigit() }) newImei2 = it }, { 
            val m = ImeiGenerator.models[Random.nextInt(ImeiGenerator.models.size)]
            selectedModel2 = m; newImei2 = ImeiGenerator.generateImei(m.tac)
        })

        Spacer(modifier = Modifier.height(32.dp))

        // Main Action Button
        Button(
            onClick = {
                scope.launch {
                    val oldI1 = imei1
                    val oldI2 = imei2
                    showProgress = true
                    val result = withContext(Dispatchers.IO) { 
                        val res = ImeiChangerUtil.writeImei(context, newImei1, newImei2)
                        delay(3000)
                        res
                    }
                    updateStatus = result.status
                    errorDetails = if (result.status != "OK") result.newWifiMac ?: "Access Denied" else ""
                    // Record IMEI change in history
                    withContext(Dispatchers.IO) {
                        try {
                            val db = AppDatabase.getDatabase(context)
                            db.imeiHistoryDao().insert(
                                ImeiHistory(
                                    timestamp = System.currentTimeMillis(),
                                    sim1OldImei = oldI1,
                                    sim1NewImei = newImei1,
                                    sim2OldImei = oldI2,
                                    sim2NewImei = newImei2,
                                    method = "Manual",
                                    success = result.status == "OK",
                                    deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                                )
                            )
                        } catch (_: Exception) { }
                    }
                    val comparisons = mutableListOf(
                        ComparisonData("IMEI 1", oldI1, newImei1),
                        ComparisonData("IMEI 2", oldI2, newImei2)
                    )
                    comparisonList = comparisons
                    showProgress = false
                    showResultDialog = true
                    loadHardwareData()
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C8CCF))
        ) {
            Text(s[4], fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // CPU & Method Info Block
        preCheckResult?.let { result ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E1E24).copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (result.isReady) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                            contentDescription = null,
                            tint = if (result.isReady) Color(0xFF4CAF50) else Color(0xFFF44336),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (result.statusMessage) {
                                "READY: All systems operational" -> s[23]
                                "FATAL: Not a System App" -> if (s.size > 24) s[24] else result.statusMessage
                                "ERROR: Signature Denied" -> if (s.size > 25) s[25] else result.statusMessage
                                "WARNING: API Restricted" -> if (s.size > 26) s[26] else result.statusMessage
                                else -> result.statusMessage
                            },
                            color = if (result.isReady) Color(0xFF4CAF50) else Color(0xFFF44336),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s[19], color = Color.Gray, fontSize = 12.sp)
                        Text(result.cpuInfo, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (s.size > 20) s[20] else "Method:", color = Color.Gray, fontSize = 12.sp)
                        Text(
                            text = when (CpuUtil.getCpuType()) {
                                CpuUtil.CpuType.MEDIATEK -> if (s.size > 27) s[27] else "MTK NVRAM + RadioEx"
                                CpuUtil.CpuType.QUALCOMM -> if (s.size > 28) s[28] else "QCOM Diag / AIDL"
                                CpuUtil.CpuType.UNISOC -> if (s.size > 29) s[29] else "SPD AT+SPIMEI"
                                CpuUtil.CpuType.EXYNOS -> if (s.size > 30) s[30] else "Samsung Exynos RIL"
                                CpuUtil.CpuType.TENSOR -> if (s.size > 31) s[31] else "Google Tensor HAL"
                                CpuUtil.CpuType.KIRIN -> if (s.size > 32) s[32] else "HiSilicon Kirin RIL"
                                else -> if (s.size > 33) s[33] else "GENERIC SYSTEM"
                            },
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun SyncProgressDialog(title: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "")
    Dialog(onDismissRequest = {}) {
        Card(modifier = Modifier.size(200.dp), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(Color(0xFF1E1E24))) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Sync, null, tint = Color(0xFF7C8CCF), modifier = Modifier.size(64.dp).rotate(rotation))
                Spacer(modifier = Modifier.height(20.dp))
                Text(title, fontSize = 14.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun UpdateResultDialog(title: String, msg: String, btnText: String, results: List<ComparisonData>, labels: List<String>, onAction: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Card(modifier = Modifier.fillMaxWidth(0.95f), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121316))) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.height(24.dp))
                results.forEach { ResultRow(it.label, it.old, it.new, labels, false); Spacer(modifier = Modifier.height(12.dp)) }
                Text(text = msg, fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C8CCF))) {
                    Text(btnText, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
fun UpdateErrorDialog(title: String, msg: String, btnText: String, detailsLabel: String, details: String, results: List<ComparisonData>, labels: List<String>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(0.95f), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121316))) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
                Spacer(modifier = Modifier.height(16.dp))
                results.forEach { ResultRow(it.label, it.old, it.new, labels, true); Spacer(modifier = Modifier.height(12.dp)) }
                Text(text = msg, fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(12.dp))
                Surface(color = Color(0xFF1E1E24), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(detailsLabel, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(details, color = Color(0xFFF44336), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF383842))) {
                    Text(btnText, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ResultRow(label: String, old: String, new: String, labels: List<String>, isError: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text(text = label, color = Color(0xFF7C8CCF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "${labels[0]}:", color = Color.Gray, fontSize = 13.sp)
            Text(text = old, color = Color.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "${labels[1]}:", color = if (isError) Color(0xFFF44336) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(text = new, color = if (isError) Color(0xFFF44336) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun IdentifierRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF9EA7C1), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ImeiInputSection(label: String, value: String, onValueChange: (String) -> Unit, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, label = { Text(label, fontSize = 11.sp, color = Color.Gray) },
            modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.Gray, unfocusedBorderColor = Color.DarkGray, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        IconButton(onClick = onRefresh, modifier = Modifier.size(54.dp).background(Color(0xFF2A2A2F), CircleShape)) {
            Icon(Icons.Rounded.Refresh, null, tint = Color.LightGray)
        }
    }
}
