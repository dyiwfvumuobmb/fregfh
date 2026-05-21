package com.android.imeisettings.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.imeisettings.data.local.SafeZone
import com.android.imeisettings.data.local.SettingsDataStore

private val trans = mapOf(
    "en" to listOf("Safe Zones", "Add Safe Zone", "This will save your current cellular area (LAC/TAC) and Cell IDs as trusted.", "Zone Name (e.g. Home)", "Save", "Cancel", "Area (LAC/TAC):", "Trusted IDs:", "Back"),
    "uk" to listOf("Безпечні зони", "Додати безпечну зону", "Це збереже поточний регіон мережі (LAC/TAC) та ID вишок як довірені.", "Назва зони (напр. Дім)", "Зберегти", "Скасувати", "Регіон (LAC/TAC):", "Довірені ID:", "Назад"),
    "ru" to listOf("Безопасные зоны", "Добавить безопасную зону", "Это сохранит текущий регион сети (LAC/TAC) и ID вышек как доверенные.", "Название зоны (напр. Дом)", "Сохранить", "Отмена", "Регион (LAC/TAC):", "Доверенные ID:", "Назад"),
    "de" to listOf("Sicherheitszonen", "Sicherheitszone hinzufügen", "Dies speichert Ihren aktuellen Funkbereich (LAC/TAC) und Ihre Zellen-IDs als vertrauenswürdig.", "Zonenname (z. B. Zuhause)", "Speichern", "Abbrechen", "Bereich (LAC/TAC):", "Vertrauenswürdige IDs:", "Zurück"),
    "pl" to listOf("Bezpieczne strefy", "Dodaj bezpieczną strefę", "To spowoduje zapisanie bieżącego obszaru komórkowego (LAC/TAC) i identyfikatorów komórek jako zaufanych.", "Nazwa strefy (np. Dom)", "Zapisz", "Anuluj", "Obszar (LAC/TAC):", "Zaufane identyfikatory:", "Wstecz"),
    "es" to listOf("Zonas seguras", "Añadir zona segura", "Esto guardará su área celular actual (LAC/TAC) e identificadores de celdas como confiables.", "Nombre de la zona (p. ej., Hogar)", "Guardar", "Cancelar", "Área (LAC/TAC):", "IDs confiables:", "Atrás"),
    "lt" to listOf("Saugumo zonos", "Pridėti saugią zoną", "Tai išsaugos jūsų dabartinę korinio ryšio sritį (LAC/TAC) ir ląstelių ID kaip patikimus.", "Zonos pavadinimas (pvz., Namai)", "Išsaugoti", "Atšaukti", "Sritis (LAC/TAC):", "Patikimi ID:", "Atgal"),
    "lv" to listOf("Drošās zonas", "Pievienot drošo zonu", "Tas saglabās jūsu pašreizējo mobilā tīkla apgabalu (LAC/TAC) un šūnu ID kā uzticamus.", "Zonas nosaukums (piem., Mājas)", "Saglabāt", "Atcelt", "Apgabals (LAC/TAC):", "Uzticamie ID:", "Atpakaļ")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeZoneScreen(
    onNavigateBack: () -> Unit,
    viewModel: SafeZoneViewModel = viewModel()
) {
    val context = LocalContext.current
    val settingsDataStore = remember { SettingsDataStore.getInstance(context) }
    val currentLang by settingsDataStore.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
    val s = trans[currentLang] ?: trans["en"]!!

    val safeZones by viewModel.safeZones.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var newZoneName by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s[1]) },
            text = {
                Column {
                    Text(s[2])
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newZoneName,
                        onValueChange = { newZoneName = it },
                        label = { Text(s[3]) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addCurrentLocationAsSafeZone(newZoneName)
                    showAddDialog = false
                    newZoneName = ""
                }) { Text(s[4]) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(s[5]) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s[0]) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s[8])
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.AddLocation, contentDescription = "Add")
            }
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize().padding(16.dp)) {
            items(safeZones) { zone ->
                SafeZoneItem(zone = zone, onDelete = { viewModel.deleteZone(zone.id) }, labels = s)
            }
        }
    }
}

@Composable
fun SafeZoneItem(zone: SafeZone, onDelete: () -> Unit, labels: List<String>) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(zone.name, fontWeight = FontWeight.Bold)
                Text("${labels[6]} ${zone.lacTac}", style = MaterialTheme.typography.bodySmall)
                Text("${labels[7]} ${zone.trustedCellIds}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
            }
        }
    }
}
