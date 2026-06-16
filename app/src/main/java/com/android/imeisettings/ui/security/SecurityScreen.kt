package com.android.imeisettings.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.SecurityLog
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.data.repository.NetworkStateTracker
import com.android.imeisettings.ui.settings.SettingsDialog
import kotlinx.coroutines.launch

private val trans = mapOf(
    "en" to listOf("Security Analysis", "SYSTEM SECURE", "THREAT DETECTED", "Interception probability", "System logs & security", "PDU Data:", "MONITORING INACTIVE", "No SIM cards detected", "Safe Zones", "Trust New Tower?", "An unknown tower was detected in your safe zone. Do you want to add it to trusted list?", "ADD", "REJECT", "Tower added to whitelist:", "WARNING"),
    "uk" to listOf("Аналіз безпеки", "СИСТЕМА БЕЗПЕЧНА", "ВИЯВЛЕНО ЗАГРОЗУ", "Ймовірність перехоплення", "Системні логи та безпека", "Дані PDU:", "МОНІТОРИНГ НЕАКТИВНИЙ", "SIM-картки не виявлені", "Безпечні зони", "Довіряти новій вишці?", "Невідома вишка була виявлена у вашій безпечній зоні. Бажаєте додати її до білого списку?", "ДОДАТИ", "ВІДХИЛИТИ", "Вишку додано до білого списку:", "ПОПЕРЕДЖЕННЯ"),
    "ru" to listOf("Анализ безопасности", "СИСТЕМА БЕЗОПАСНА", "ОБНАРУЖЕНА УГРОЗА", "Вероятность перехвата", "Системные логи и безопасность", "Данные PDU:", "МОНИТОРИНГ НЕАКТИВЕН", "SIM-карты не обнаружены", "Безопасные зоны", "Доверять новой вышке?", "Неизвестная вышка была обнаружена в вашей безопасной зоне. Хотите добавить ее в белый список?", "ДОБАВИТЬ", "ОТКЛОНИТЬ", "Вышка добавлена в белый список:", "ВНИМАНИЕ"),
    "de" to listOf("Sicherheitsanalyse", "SYSTEM SICHER", "BEDROHUNG ERKANNT", "Abhörwahrscheinlichkeit", "Systemprotokolle", "PDU-Daten:", "MONITORING INAKTIV", "Keine SIM-Karten", "Sicherheitszonen", "Neuer Funkmast vertrauen?", "Ein unbekannter Funkmast wurde in Ihrer Sicherheitszone erkannt. Zur Liste hinzufügen?", "HINZUFÜGEN", "ABLEHNEN", "Funkmast zur Whitelist hinzugefügt:", "WARNUNG"),
    "pl" to listOf("Analiza bezpieczeństwa", "SYSTEM BEZPIECZNY", "WYKRYTO ZAGROŻENIE", "Prawdopodobieństwo przechwycenia", "Logi systemowe", "Dane PDU:", "MONITOROWANIE NIEAKTYWNE", "Brak kart SIM", "Bezpieczne strefy", "Zaufać nowej wieży?", "Wykryto nieznaną wieżę w Twojej bezpiecznej strefie. Czy chcesz dodać ją do zaufanych?", "DODAJ", "ODRZUĆ", "Wieża dodana do białej listy:", "OSTRZEŻENIE"),
    "es" to listOf("Análisis de seguridad", "SISTEMA SEGURO", "AMENAZA DETECTADA", "Probabilidad de interceptación", "Registros del sistema", "Datos PDU:", "MONITOREO INACTIVO", "Sin tarjetas SIM", "Zonas seguras", "¿Confiar en la nueva torre?", "Se detectó una torre desconocida en su zona segura. ¿Desea añadirla a la lista?", "AÑADIR", "RECHAZAR", "Torre añadida a la lista blanca:", "ADVERTENCIA"),
    "lt" to listOf("Saugumo analizė", "SISTEMA SAUGI", "APTIKTA GRĖSMĖ", "Perėmimo tikimybė", "Sistemos žurnalai", "PDU duomenys:", "MONITORINGAS NEAKTYVUS", "SIM kortelių neaptikta", "Saugios zonos", "Pasitikėti nauju bokštu?", "Jūsų saugioje zonoje aptiktas nežinomas bokštas. Ar norite jį pridėti prie patikimų?", "PRIDĖTI", "ATMESTI", "Bokštas pridėtas prie baltojo sąrašo:", "ĮSPĖJIMAS"),
    "lv" to listOf("Drošības analīze", "SISTĒMA DROŠA", "ATKLĀTI DRAUDI", "Pārtveršanas iespējamība", "Sistēmas žurnāli", "PDU dati:", "MONITORINGS NEAKTĪVS", "SIM kartes nav konstatētas", "Drošās zonas", "Uzticēties jaunajam tornim?", "Jūsu drošajā zonā tika pamanīts nezināms tornis. Vai vēlaties to pievienot?", "PIEVIENOT", "NORAIDĪT", "Tornis pievienots baltajam sarakstam:", "BRĪDINĀJUMS")
)

@Composable
fun SecurityScreen(
    onNavigateToSafeZones: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    pendingCellId: String? = null,
    pendingZoneId: Int = -1,
    onClearPending: () -> Unit = {},
    safeZoneViewModel: SafeZoneViewModel = viewModel()
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val logs by db.securityLogDao().getAllLogs().collectAsStateWithLifecycle(initialValue = emptyList())
    val settingsDataStore = remember { SettingsDataStore.getInstance(context) }
    val currentLang by settingsDataStore.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
    val scope = rememberCoroutineScope()
    
    val totalThreat by NetworkStateTracker.totalThreatLevel.collectAsStateWithLifecycle()
    
    val s = trans[currentLang] ?: trans["uk"]!!

    val isSecure = totalThreat in 0..24
    val isWarning = totalThreat in 25..49
    val isThreat = totalThreat >= 50
    val isNoSim = totalThreat == -1

    if (pendingCellId != null && pendingZoneId != -1) {
        AlertDialog(
            onDismissRequest = onClearPending,
            title = { Text(s[9]) },
            text = { Text("${s[10]}\n\nCell ID: $pendingCellId") },
            confirmButton = {
                Button(onClick = {
                    safeZoneViewModel.addCellToSafeZone(pendingZoneId, pendingCellId)
                    scope.launch {
                        db.securityLogDao().insertLog(SecurityLog(0, System.currentTimeMillis(), "GEO_FENCE", "${s[13]} $pendingCellId"))
                    }
                    onClearPending()
                }) { Text(s[11]) }
            },
            dismissButton = {
                TextButton(onClick = onClearPending) { Text(s[12]) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121216)).padding(horizontal = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = s[0], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Row {
                IconButton(onClick = onNavigateToSafeZones) {
                    Icon(Icons.Rounded.LocationOn, null, tint = Color.White)
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Rounded.Settings, null, tint = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E24), RoundedCornerShape(20.dp)).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    isNoSim -> Icons.Rounded.Block
                    isThreat -> Icons.Rounded.GppMaybe
                    isWarning -> Icons.Rounded.Warning
                    else -> Icons.Rounded.Shield
                },
                contentDescription = null,
                tint = when {
                    isNoSim -> Color.Gray
                    isThreat -> Color(0xFFF44336)
                    isWarning -> Color(0xFFFFC107)
                    else -> Color(0xFF4CAF50)
                },
                modifier = Modifier.size(52.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = when {
                        isNoSim -> s[6]
                        isThreat -> s[2]
                        isWarning -> s[14]
                        else -> s[1]
                    }, 
                    fontSize = 17.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = when {
                        isNoSim -> Color.Gray
                        isThreat -> Color(0xFFF44336)
                        isWarning -> Color(0xFFFFC107)
                        else -> Color(0xFF4CAF50)
                    }
                )
                Text(
                    text = if (isNoSim) s[7] else "${s[3]}: $totalThreat%", 
                    fontSize = 12.sp, 
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = s[4], fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            IconButton(onClick = { scope.launch { db.securityLogDao().deleteAllLogs() } }) {
                Icon(Icons.Rounded.DeleteSweep, null, tint = Color.DarkGray, modifier = Modifier.size(18.dp))
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(logs, key = { it.id }) { log ->
                val visible = remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible.value = true }
                
                androidx.compose.animation.AnimatedVisibility(
                    visible = visible.value,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn()
                ) {
                    SecurityEventRow(log, s[5])
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun SecurityScreenPreview() {
    com.android.imeisettings.ui.theme.CONSULIMEITheme {
        SecurityScreen()
    }
}

@Composable
fun SecurityEventRow(log: SecurityLog, pduLabel: String) {
    var expanded by remember { mutableStateOf(false) }
    val color = when(log.type) {
        "ALERT" -> Color(0xFFF44336)
        "WARNING" -> Color(0xFFFF9800)
        "ASN_CHECK" -> Color(0xFF00BCD4)
        "LEARNING" -> Color(0xFF00E5FF)
        "GEO_FENCE" -> Color(0xFFE91E63)
        "MONITOR" -> Color(0xFF607D8B)
        else -> Color(0xFF9E9E9E)
    }

    val timeFormatter = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    val timeStr = timeFormatter.format(java.util.Date(log.timestamp))

    Column(modifier = Modifier.fillMaxWidth().clickable(enabled = log.pdu != null) { expanded = !expanded }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(55.dp)) {
                Text(text = timeStr, fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Box(modifier = Modifier.size(2.dp, 12.dp).background(color.copy(alpha = 0.3f)))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.Circle, 
                    null, 
                    tint = color, 
                    modifier = Modifier.size(if (expanded) 18.dp else 8.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.message, 
                    fontSize = 13.sp, 
                    color = if (log.type == "ALERT" || log.type == "GEO_FENCE") color else Color.LightGray,
                    fontWeight = if (log.type == "ALERT" || log.type == "GEO_FENCE" || log.type == "LEARNING") FontWeight.Bold else FontWeight.Normal,
                    lineHeight = 18.sp
                )
                if (log.pdu != null && !expanded) {
                    Text(
                        text = "Tap to view PDU details",
                        fontSize = 10.sp,
                        color = Color.Gray.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Light
                    )
                }
            }
        }
        if (expanded && log.pdu != null) {
            Surface(
                modifier = Modifier.padding(start = 26.dp, top = 8.dp, bottom = 4.dp).fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = pduLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = log.pdu,
                        fontSize = 10.sp,
                        color = Color.Gray,
                        lineHeight = 14.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}


