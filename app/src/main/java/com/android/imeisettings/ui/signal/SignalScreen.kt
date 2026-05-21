package com.android.imeisettings.ui.signal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.data.model.NetworkDetails
import com.android.imeisettings.data.repository.AsnDatabase
import com.android.imeisettings.data.repository.NetworkStateTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// Comprehensive Signal Screen Translations
private val trans = mapOf(
    "en" to mapOf(
        "network" to "NETWORK", "signal" to "SIGNAL", "core_info" to "CORE NETWORK INFO",
        "operator" to "OPERATOR", "mcc_mnc" to "MCC / MNC", "asn_info" to "ASN INFO",
        "cell_id" to "CELL ID", "lac_tac" to "LAC / TAC", "pci_psc" to "PCI / PSC",
        "arfcn" to "ARFCN", "band" to "BAND", "neighbors" to "NEIGHBORS",
        "roaming" to "ROAMING", "encryption" to "ENCRYPTION", "trust_btn" to "Confirm Trust to this ASN",
        "alert_neighbors" to "ALERT: Possible fake base station (No neighbors)!", "no_sim" to "NO SIM CARD DETECTED",
        "state_on" to "ON", "state_off" to "OFF", "state_in_service" to "IN SERVICE", "state_searching" to "SEARCHING...",
        "state_na" to "N/A", "state_auto" to "Auto"
    ),
    "uk" to mapOf(
        "network" to "МЕРЕЖА", "signal" to "СИГНАЛ", "core_info" to "ОСНОВНА ІНФОРМАЦІЯ",
        "operator" to "ОПЕРАТОР", "mcc_mnc" to "MCC / MNC", "asn_info" to "ASN ІНФО",
        "cell_id" to "CELL ID", "lac_tac" to "LAC / TAC", "pci_psc" to "PCI / PSC",
        "arfcn" to "ARFCN", "band" to "ДІАПАЗОН", "neighbors" to "СУСІДИ",
        "roaming" to "РОУМІНГ", "encryption" to "ШИФРУВАННЯ", "trust_btn" to "Підтвердити довіру до цього ASN",
        "alert_neighbors" to "УВАГА: Можлива фейкова вишка (немає сусідів)!", "no_sim" to "SIM-КАРТКИ НЕ ВИЯВЛЕНО",
        "state_on" to "УВІМК", "state_off" to "ВИМК", "state_in_service" to "В МЕРЕЖІ", "state_searching" to "ПОШУК...",
        "state_na" to "Н/Д", "state_auto" to "Авто"
    ),
    "ru" to mapOf(
        "network" to "СЕТЬ", "signal" to "СИГНАЛ", "core_info" to "ОСНОВНАЯ ИНФОРМАЦИЯ",
        "operator" to "ОПЕРАТОР", "mcc_mnc" to "MCC / MNC", "asn_info" to "ASN ИНФО",
        "cell_id" to "CELL ID", "lac_tac" to "LAC / TAC", "pci_psc" to "PCI / PSC",
        "arfcn" to "ARFCN", "band" to "ДИАПАЗОН", "neighbors" to "СОСЕДИ",
        "roaming" to "РОУМИНГ", "encryption" to "ШИФРОВАНИЕ", "trust_btn" to "Подтвердить доверие к этому ASN",
        "alert_neighbors" to "ВНИМАНИЕ: Возможная фейковая вышка (нет соседей)!", "no_sim" to "SIM-КАРТЫ НЕ ОБНАРУЖЕНЫ",
        "state_on" to "ВКЛ", "state_off" to "ВЫКЛ", "state_in_service" to "В СЕТИ", "state_searching" to "ПОИСК...",
        "state_na" to "Н/Д", "state_auto" to "Авто"
    ),
    "de" to mapOf(
        "network" to "NETZWERK", "signal" to "SIGNAL", "core_info" to "NETZWERK-INFO",
        "operator" to "BETREIBER", "mcc_mnc" to "MCC / MNC", "asn_info" to "ASN INFO",
        "cell_id" to "CELL ID", "lac_tac" to "LAC / TAC", "pci_psc" to "PCI / PSC",
        "arfcn" to "ARFCN", "band" to "BAND", "neighbors" to "NACHBARN",
        "roaming" to "ROAMING", "encryption" to "VERSCHLÜSSELUNG", "trust_btn" to "Diesem ASN vertrauen",
        "alert_neighbors" to "ALARM: Mögliche gefälschte Basisstation!", "no_sim" to "KEINE SIM-KARTE ERKANNT"
    ),
    "lt" to mapOf(
        "network" to "TINKLAS", "signal" to "SIGNALAS", "core_info" to "TINKLO INFORMACIJA",
        "operator" to "OPERATORIUS", "mcc_mnc" to "MCC / MNC", "asn_info" to "ASN INFO",
        "cell_id" to "CELL ID", "lac_tac" to "LAC / TAC", "pci_psc" to "PCI / PSC",
        "arfcn" to "ARFCN", "band" to "DAŽNIS", "neighbors" to "KAIMYNAI",
        "roaming" to "ROUMINGAS", "encryption" to "ŠIFRAVIMAS", "trust_btn" to "Patvirtinti pasitikėjimą šiuo ASN",
        "alert_neighbors" to "ĮSPĖJIMAS: Galima netikra bazinė stotis!", "no_sim" to "SIM KORTELĖ NERASTA"
    ),
    "lv" to mapOf(
        "network" to "TĪKLS", "signal" to "SIGNĀLS", "core_info" to "TĪKLA INFORMĀCIJA",
        "operator" to "OPERATORS", "mcc_mnc" to "MCC / MNC", "asn_info" to "ASN INFO",
        "cell_id" to "CELL ID", "lac_tac" to "LAC / TAC", "pci_psc" to "PCI / PSC",
        "arfcn" to "ARFCN", "band" to "JOSLA", "neighbors" to "KAIMIŅI",
        "roaming" to "ROUMINGS", "encryption" to "ŠIFRĒŠANA", "trust_btn" to "Apstiprināt uzticību šim ASN",
        "alert_neighbors" to "BRĪDINĀJUMS: Iespējama viltus bāzes stacija!", "no_sim" to "SIM KARTE NETIKA ATRASTA"
    ),
    "pl" to mapOf(
        "network" to "SIEĆ", "signal" to "SYGNAŁ", "core_info" to "INFORMACJE O SIECI",
        "operator" to "OPERATOR", "mcc_mnc" to "MCC / MNC", "asn_info" to "ASN INFO",
        "cell_id" to "CELL ID", "lac_tac" to "LAC / TAC", "pci_psc" to "PCI / PSC",
        "arfcn" to "ARFCN", "band" to "PASMO", "neighbors" to "SĄSIEDZI",
        "roaming" to "ROAMING", "encryption" to "SZYFROWANIE", "trust_btn" to "Potwierdź zaufanie do tego ASN",
        "alert_neighbors" to "ALARM: Możliwa fałszywa stacja bazowa!", "no_sim" to "BRAK KARTY SIM"
    ),
    "es" to mapOf(
        "network" to "RED", "signal" to "SEÑAL", "core_info" to "INFORMACIÓN DE RED",
        "operator" to "OPERADOR", "mcc_mnc" to "MCC / MNC", "asn_info" to "INFO ASN",
        "cell_id" to "ID DE CELDA", "lac_tac" to "LAC / TAC", "pci_psc" to "PCI / PSC",
        "arfcn" to "ARFCN", "band" to "BANDA", "neighbors" to "VECINOS",
        "roaming" to "ROAMING", "encryption" to "CIFRADO", "trust_btn" to "Confirmar confianza en este ASN",
        "alert_neighbors" to "ALERTA: ¡Posible estación base falsa!", "no_sim" to "NO SE DETECTÓ TARJETA SIM"
    )
)

@Composable
fun SignalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsDataStore = remember { SettingsDataStore.getInstance(context) }
    val currentLang by settingsDataStore.selectedLanguage.collectAsStateWithLifecycle(initialValue = "en")
    val userTrustedAsns by settingsDataStore.userTrustedAsns.collectAsStateWithLifecycle(initialValue = emptySet())

    val s = trans[currentLang] ?: trans["en"]!!

    var selectedTab by remember { mutableIntStateOf(0) }
    
    val detailsSim1 by NetworkStateTracker.networkDetailsSim1.collectAsStateWithLifecycle()
    val detailsSim2 by NetworkStateTracker.networkDetailsSim2.collectAsStateWithLifecycle()
    
    val currentDetails = if (selectedTab == 0) detailsSim1 else detailsSim2

    val historySim1 = remember { mutableStateListOf<Float>().apply { repeat(50) { add(0.1f) } } }
    val historySim2 = remember { mutableStateListOf<Float>().apply { repeat(50) { add(0.1f) } } }
    
    val currentHistory = if (selectedTab == 0) historySim1 else historySim2

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            updateGraph(historySim1, detailsSim1.signalDbm)
            updateGraph(historySim2, detailsSim2.signalDbm)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121216))) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
            Text(text = "CONSUL IMEI", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = Color(0xFF7C8CCF),
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tabPositions[selectedTab]), color = Color(0xFF7C8CCF))
            },
            divider = {}
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("SIM 1", fontWeight = FontWeight.Bold, fontSize = 16.sp) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("SIM 2", fontWeight = FontWeight.Bold, fontSize = 16.sp) })
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (currentDetails.voiceState == "NO SIM") {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text(s["no_sim"]!!, color = Color.Gray, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(s["network"]!!, fontSize = 11.sp, color = Color.Gray)
                        Text(currentDetails.networkType, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s["signal"]!!, fontSize = 11.sp, color = Color.Gray)
                        val dbm = currentDetails.signalDbm
                        Text(if (dbm <= -140) "---" else "$dbm dBm", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC107))
                    }
                    Canvas(modifier = Modifier.size(width = 130.dp, height = 45.dp)) {
                        val path = Path()
                        if (currentHistory.isNotEmpty()) {
                            val stepX = size.width / (currentHistory.size - 1)
                            path.moveTo(0f, size.height * (1f - currentHistory[0]))
                            for (i in 1 until currentHistory.size) {
                                path.lineTo(i * stepX, size.height * (1f - currentHistory[i]))
                            }
                        }
                        drawPath(path, color = Color(0xFF4CAF50), style = Stroke(width = 2.dp.toPx()))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(text = s["core_info"]!!, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C8CCF).copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 8.dp))

                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E24), RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    InfoRow(s["operator"]!!, if (currentDetails.voiceState == "state_searching") s["state_searching"]!! else currentDetails.operator)
                    InfoRow(s["mcc_mnc"]!!, currentDetails.mccMnc)
                    
                    AsnInfoRow(
                        label = s["asn_info"]!!,
                        value = currentDetails.asnInfo,
                        asnNumber = currentDetails.asnNumber,
                        isTrusted = AsnDatabase.isTrusted(currentDetails.asnNumber) || userTrustedAsns.contains(currentDetails.asnNumber),
                        btnText = s["trust_btn"]!!,
                        onTrustClick = {
                            scope.launch { settingsDataStore.trustAsn(currentDetails.asnNumber) }
                        }
                    )

                    InfoRow(s["cell_id"]!!, currentDetails.cellId)
                    InfoRow(s["lac_tac"]!!, currentDetails.lacTac)
                    InfoRow(s["pci_psc"]!!, currentDetails.pciPsc)
                    InfoRow(s["arfcn"]!!, currentDetails.arfcn)
                    InfoRow(s["band"]!!, if (currentDetails.band.isEmpty() || currentDetails.band == "Auto") s["state_auto"]!! else currentDetails.band)
                    InfoRow(
                        label = s["neighbors"]!!,
                        value = currentDetails.neighbors.toString(),
                        isWarning = currentDetails.neighbors == 0 && currentDetails.networkType != "N/A" && currentDetails.networkType != "Searching..."
                    )
                    InfoRow(s["roaming"]!!, s[currentDetails.roaming] ?: currentDetails.roaming)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E24), RoundedCornerShape(10.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s["encryption"]!!, fontSize = 13.sp, color = Color.Gray)
                    Text(text = currentDetails.encryption, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (currentDetails.encryption.contains("SECURED") || currentDetails.encryption.contains("SECURE")) Color(0xFF4CAF50) else Color.White)
                }
            }
        }
    }
}

private fun updateGraph(list: MutableList<Float>, dbm: Int) {
    val base = ((dbm + 140).toFloat() / 100f).coerceIn(0.1f, 0.9f)
    val jitter = (Random.nextFloat() - 0.5f) * 0.03f
    if (list.size >= 50) list.removeAt(0)
    list.add((base + jitter).coerceIn(0.05f, 0.95f))
}

@Composable
fun InfoRow(label: String, value: String, isSecure: Boolean = false, isWarning: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 13.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isWarning) {
                Icon(Icons.Rounded.Warning, null, tint = Color.Red, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (isSecure) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Rounded.Shield, null, tint = Color(0xFF7C8CCF), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun AsnInfoRow(label: String, value: String, asnNumber: String, isTrusted: Boolean, btnText: String, onTrustClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, fontSize = 13.sp, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = if (value == "N/A" || value == "Loading..." || value == "VPN Protected" || value == "---" || value == "Wi-Fi") Color.White 
                            else if (isTrusted) Color(0xFF4CAF50) else Color.Red
                Text(text = if (asnNumber.isNotEmpty()) asnNumber else value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
                if (isTrusted && asnNumber.isNotEmpty() && value != "N/A" && value != "VPN Protected") {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Rounded.Verified, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                }
            }
        }
        
        if (!isTrusted && asnNumber.isNotEmpty() && value != "VPN Protected" && value != "N/A" && value != "Loading..." && value != "---" && value != "Wi-Fi") {
            Button(
                onClick = onTrustClick,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp).height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text(btnText, fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
