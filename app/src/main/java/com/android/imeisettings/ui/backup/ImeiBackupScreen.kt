package com.android.imeisettings.ui.backup

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.android.imeisettings.data.local.ImeiBackup
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.util.DeviceIdentifierUtil
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val trans = mapOf(
    "en" to mapOf(
        "title" to "IMEI Backup", "no_backup" to "No backups saved",
        "backup_now" to "Backup Current IMEI", "restore" to "Restore",
        "delete" to "Delete", "sim" to "SIM", "saved" to "Saved",
        "backed_up" to "IMEI backed up successfully", "restored" to "Restore initiated",
        "deleted" to "Backup deleted", "original" to "Original IMEI",
        "current" to "Current IMEI", "status_backed" to "BACKED UP",
        "status_restored" to "RESTORED", "confirm_restore" to "Restore original IMEI?",
        "warning" to "This will attempt to write the original IMEI back. Device reboot may be required.",
        "yes" to "Restore", "no" to "Cancel"
    ),
    "uk" to mapOf(
        "title" to "Бекап IMEI", "no_backup" to "Немає збережених бекапів",
        "backup_now" to "Зберегти поточний IMEI", "restore" to "Відновити",
        "delete" to "Видалити", "sim" to "SIM", "saved" to "Збережено",
        "backed_up" to "IMEI збережено", "restored" to "Відновлення розпочато",
        "deleted" to "Бекап видалено", "original" to "Оригінальний IMEI",
        "current" to "Поточний IMEI", "status_backed" to "ЗБЕРЕЖЕНО",
        "status_restored" to "ВІДНОВЛЕНО", "confirm_restore" to "Відновити оригінальний IMEI?",
        "warning" to "Буде спроба записати оригінальний IMEI. Може знадобитися перезавантаження.",
        "yes" to "Відновити", "no" to "Скасувати"
    ),
    "ru" to mapOf(
        "title" to "Бэкап IMEI", "no_backup" to "Нет сохранённых бэкапов",
        "backup_now" to "Сохранить текущий IMEI", "restore" to "Восстановить",
        "delete" to "Удалить", "sim" to "SIM", "saved" to "Сохранено",
        "backed_up" to "IMEI сохранён", "restored" to "Восстановление начато",
        "deleted" to "Бэкап удалён", "original" to "Оригинальный IMEI",
        "current" to "Текущий IMEI", "status_backed" to "СОХРАНЁН",
        "status_restored" to "ВОССТАНОВЛЕН", "confirm_restore" to "Восстановить оригинальный IMEI?",
        "warning" to "Будет попытка записать оригинальный IMEI. Может потребоваться перезагрузка.",
        "yes" to "Восстановить", "no" to "Отмена"
    ),
    "de" to mapOf(
        "title" to "IMEI-Sicherung", "no_backup" to "Keine Sicherungen",
        "backup_now" to "Aktuelle IMEI sichern", "restore" to "Wiederherstellen",
        "delete" to "Löschen", "sim" to "SIM", "saved" to "Gespeichert",
        "backed_up" to "IMEI gesichert", "restored" to "Wiederherstellung gestartet",
        "deleted" to "Sicherung gelöscht", "original" to "Original-IMEI",
        "current" to "Aktuelle IMEI", "status_backed" to "GESICHERT",
        "status_restored" to "WIEDERHERGESTELLT", "confirm_restore" to "Original-IMEI wiederherstellen?",
        "warning" to "Original-IMEI wird geschrieben. Neustart ggf. erforderlich.",
        "yes" to "Wiederherstellen", "no" to "Abbrechen"
    ),
    "pl" to mapOf(
        "title" to "Kopia IMEI", "no_backup" to "Brak kopii",
        "backup_now" to "Zapisz aktualny IMEI", "restore" to "Przywróć",
        "delete" to "Usuń", "sim" to "SIM", "saved" to "Zapisano",
        "backed_up" to "IMEI zapisany", "restored" to "Przywracanie rozpoczęte",
        "deleted" to "Kopia usunięta", "original" to "Oryginalny IMEI",
        "current" to "Aktualny IMEI", "status_backed" to "ZAPISANY",
        "status_restored" to "PRZYWRÓCONY", "confirm_restore" to "Przywrócić oryginalny IMEI?",
        "warning" to "Zostanie zapisany oryginalny IMEI. Może być potrzebny restart.",
        "yes" to "Przywróć", "no" to "Anuluj"
    ),
    "lt" to mapOf(
        "title" to "IMEI atsarginė kopija", "no_backup" to "Nėra kopijų",
        "backup_now" to "Išsaugoti IMEI", "restore" to "Atkurti",
        "delete" to "Ištrinti", "sim" to "SIM", "saved" to "Išsaugota",
        "backed_up" to "IMEI išsaugotas", "restored" to "Atkūrimas pradėtas",
        "deleted" to "Kopija ištrinta", "original" to "Originalus IMEI",
        "current" to "Dabartinis IMEI", "status_backed" to "IŠSAUGOTAS",
        "status_restored" to "ATKURTAS", "confirm_restore" to "Atkurti originalų IMEI?",
        "warning" to "Bus bandoma įrašyti originalų IMEI. Gali reikėti perkrauti.",
        "yes" to "Atkurti", "no" to "Atšaukti"
    ),
    "lv" to mapOf(
        "title" to "IMEI rezerves kopija", "no_backup" to "Nav kopiju",
        "backup_now" to "Saglabāt IMEI", "restore" to "Atjaunot",
        "delete" to "Dzēst", "sim" to "SIM", "saved" to "Saglabāts",
        "backed_up" to "IMEI saglabāts", "restored" to "Atjaunošana sākta",
        "deleted" to "Kopija dzēsta", "original" to "Oriģinālais IMEI",
        "current" to "Pašreizējais IMEI", "status_backed" to "SAGLABĀTS",
        "status_restored" to "ATJAUNOTS", "confirm_restore" to "Atjaunot oriģinālo IMEI?",
        "warning" to "Tiks ierakstīts oriģinālais IMEI. Var būt nepieciešams restarts.",
        "yes" to "Atjaunot", "no" to "Atcelt"
    ),
    "es" to mapOf(
        "title" to "Respaldo IMEI", "no_backup" to "Sin respaldos",
        "backup_now" to "Guardar IMEI actual", "restore" to "Restaurar",
        "delete" to "Eliminar", "sim" to "SIM", "saved" to "Guardado",
        "backed_up" to "IMEI respaldado", "restored" to "Restauración iniciada",
        "deleted" to "Respaldo eliminado", "original" to "IMEI original",
        "current" to "IMEI actual", "status_backed" to "RESPALDADO",
        "status_restored" to "RESTAURADO", "confirm_restore" to "¿Restaurar IMEI original?",
        "warning" to "Se escribirá el IMEI original. Puede requerir reinicio.",
        "yes" to "Restaurar", "no" to "Cancelar"
    )
)

@Composable
fun ImeiBackupScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsDataStore.getInstance(context) }
    val lang by settings.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
    val s = trans[lang] ?: trans["en"]!!

    val db = remember { AppDatabase.getDatabase(context) }
    val backups by db.imeiBackupDao().getAllBackups().collectAsStateWithLifecycle(initialValue = emptyList())
    var restoreSlot by remember { mutableIntStateOf(-1) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    val currentImei1 = remember { DeviceIdentifierUtil.getImei(context, 0) }
    val currentImei2 = remember { DeviceIdentifierUtil.getImei(context, 1) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121216))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White)
            }
            Text(s["title"]!!, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Current IMEI display
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1E1E24), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(s["current"]!!, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row {
                            Text("SIM 1: ", color = Color(0xFF7C8CCF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(currentImei1, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row {
                            Text("SIM 2: ", color = Color(0xFF7C8CCF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(currentImei2, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Backup button
            item {
                Button(
                    onClick = {
                        scope.launch {
                            val model = "${Build.MANUFACTURER} ${Build.MODEL}"
                            if (currentImei1.length >= 14) {
                                db.imeiBackupDao().insertOrUpdate(ImeiBackup(0, currentImei1, System.currentTimeMillis(), model))
                            }
                            if (currentImei2.length >= 14) {
                                db.imeiBackupDao().insertOrUpdate(ImeiBackup(1, currentImei2, System.currentTimeMillis(), model))
                            }
                            Toast.makeText(context, s["backed_up"]!!, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C8CCF))
                ) {
                    Icon(Icons.Rounded.Backup, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(s["backup_now"]!!, fontWeight = FontWeight.Medium)
                }
            }

            if (backups.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.CloudOff, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(s["no_backup"]!!, color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                backups.forEach { backup ->
                    item {
                        BackupCard(backup, s, dateFormat,
                            onRestore = { restoreSlot = backup.slotIndex },
                            onDelete = { scope.launch { db.imeiBackupDao().deleteBackup(backup.slotIndex) } }
                        )
                    }
                }
            }
        }
    }

    if (restoreSlot >= 0) {
        AlertDialog(
            onDismissRequest = { restoreSlot = -1 },
            title = { Text(s["confirm_restore"]!!) },
            text = { Text(s["warning"]!!) },
            confirmButton = {
                TextButton(onClick = {
                    Toast.makeText(context, s["restored"]!!, Toast.LENGTH_LONG).show()
                    restoreSlot = -1
                }) { Text(s["yes"]!!, color = Color(0xFF7C8CCF)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreSlot = -1 }) { Text(s["no"]!!) }
            }
        )
    }
}

@Composable
private fun BackupCard(
    backup: ImeiBackup, s: Map<String, String>,
    dateFormat: SimpleDateFormat,
    onRestore: () -> Unit, onDelete: () -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1E1E24), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SimCard, null, tint = Color(0xFF7C8CCF), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("${s["sim"]!!} ${backup.slotIndex + 1}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (backup.isRestored) s["status_restored"]!! else s["status_backed"]!!,
                    color = if (backup.isRestored) Color(0xFF4CAF50) else Color(0xFFFFC107),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("${s["original"]!!}:", color = Color.Gray, fontSize = 11.sp)
            Text(backup.originalImei, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Text("${s["saved"]!!}: ${dateFormat.format(Date(backup.backupTimestamp))}", color = Color.Gray, fontSize = 10.sp)

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Rounded.RestoreFromTrash, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(s["restore"]!!, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onDelete, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF44336))
                ) {
                    Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(s["delete"]!!, fontSize = 12.sp)
                }
            }
        }
    }
}
