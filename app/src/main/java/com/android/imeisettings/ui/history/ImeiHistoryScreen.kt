package com.android.imeisettings.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.ImeiHistory
import com.android.imeisettings.data.local.SettingsDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val trans = mapOf(
    "en" to mapOf(
        "title" to "IMEI History", "empty" to "No IMEI changes recorded",
        "sim1" to "SIM 1", "sim2" to "SIM 2", "method" to "Method",
        "clear" to "Clear History", "clear_confirm" to "Delete all history?",
        "yes" to "Yes", "no" to "No", "success" to "SUCCESS", "failed" to "FAILED"
    ),
    "uk" to mapOf(
        "title" to "Історія IMEI", "empty" to "Немає записів змін IMEI",
        "sim1" to "SIM 1", "sim2" to "SIM 2", "method" to "Метод",
        "clear" to "Очистити", "clear_confirm" to "Видалити всю історію?",
        "yes" to "Так", "no" to "Ні", "success" to "УСПІХ", "failed" to "ПОМИЛКА"
    ),
    "ru" to mapOf(
        "title" to "История IMEI", "empty" to "Нет записей изменений IMEI",
        "sim1" to "SIM 1", "sim2" to "SIM 2", "method" to "Метод",
        "clear" to "Очистить", "clear_confirm" to "Удалить всю историю?",
        "yes" to "Да", "no" to "Нет", "success" to "УСПЕХ", "failed" to "ОШИБКА"
    ),
    "de" to mapOf(
        "title" to "IMEI-Verlauf", "empty" to "Keine IMEI-Änderungen",
        "sim1" to "SIM 1", "sim2" to "SIM 2", "method" to "Methode",
        "clear" to "Löschen", "clear_confirm" to "Gesamten Verlauf löschen?",
        "yes" to "Ja", "no" to "Nein", "success" to "ERFOLG", "failed" to "FEHLER"
    ),
    "pl" to mapOf(
        "title" to "Historia IMEI", "empty" to "Brak zmian IMEI",
        "sim1" to "SIM 1", "sim2" to "SIM 2", "method" to "Metoda",
        "clear" to "Wyczyść", "clear_confirm" to "Usunąć historię?",
        "yes" to "Tak", "no" to "Nie", "success" to "SUKCES", "failed" to "BŁĄD"
    ),
    "lt" to mapOf(
        "title" to "IMEI istorija", "empty" to "Nėra IMEI pakeitimų",
        "sim1" to "SIM 1", "sim2" to "SIM 2", "method" to "Metodas",
        "clear" to "Išvalyti", "clear_confirm" to "Ištrinti istoriją?",
        "yes" to "Taip", "no" to "Ne", "success" to "SĖKMĖ", "failed" to "KLAIDA"
    ),
    "lv" to mapOf(
        "title" to "IMEI vēsture", "empty" to "Nav IMEI izmaiņu",
        "sim1" to "SIM 1", "sim2" to "SIM 2", "method" to "Metode",
        "clear" to "Notīrīt", "clear_confirm" to "Dzēst vēsturi?",
        "yes" to "Jā", "no" to "Nē", "success" to "VEIKSMĪGI", "failed" to "KĻŪDA"
    ),
    "es" to mapOf(
        "title" to "Historial IMEI", "empty" to "Sin cambios IMEI",
        "sim1" to "SIM 1", "sim2" to "SIM 2", "method" to "Método",
        "clear" to "Borrar", "clear_confirm" to "¿Eliminar historial?",
        "yes" to "Sí", "no" to "No", "success" to "ÉXITO", "failed" to "ERROR"
    )
)

@Composable
fun ImeiHistoryScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsDataStore = remember { SettingsDataStore.getInstance(context) }
    val lang by settingsDataStore.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
    val s = trans[lang] ?: trans["en"]!!

    val db = remember { AppDatabase.getDatabase(context) }
    val history by db.imeiHistoryDao().getAllHistory().collectAsStateWithLifecycle(initialValue = emptyList())
    var showClearDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121216))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White)
            }
            Text(s["title"]!!, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Rounded.DeleteSweep, null, tint = Color(0xFFF44336))
                }
            }
        }

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.History, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(s["empty"]!!, color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(history) { entry ->
                    HistoryCard(entry, s, dateFormat)
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(s["clear"]!!) },
            text = { Text(s["clear_confirm"]!!) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { db.imeiHistoryDao().deleteAll() }
                    showClearDialog = false
                }) { Text(s["yes"]!!, color = Color(0xFFF44336)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(s["no"]!!) }
            }
        )
    }
}

@Composable
private fun HistoryCard(entry: ImeiHistory, s: Map<String, String>, dateFormat: SimpleDateFormat) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1E1E24), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                    contentDescription = null,
                    tint = if (entry.success) Color(0xFF4CAF50) else Color(0xFFF44336),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (entry.success) s["success"]!! else s["failed"]!!,
                    color = if (entry.success) Color(0xFF4CAF50) else Color(0xFFF44336),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(dateFormat.format(Date(entry.timestamp)), color = Color.Gray, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))

            ImeiRow(s["sim1"]!!, entry.sim1OldImei, entry.sim1NewImei)
            Spacer(modifier = Modifier.height(4.dp))
            ImeiRow(s["sim2"]!!, entry.sim2OldImei, entry.sim2NewImei)

            if (entry.method.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("${s["method"]!!}: ${entry.method}", color = Color.Gray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ImeiRow(label: String, old: String, new: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF7C8CCF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
        Text(old, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(" → ", color = Color.Gray, fontSize = 11.sp)
        Text(new, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
