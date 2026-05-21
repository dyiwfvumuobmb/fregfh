package com.android.imeisettings.ui.settings

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    settingsDataStore: SettingsDataStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var showAbout by remember { mutableStateOf(false) }
    var currentLang by remember { mutableStateOf("uk") }

    LaunchedEffect(Unit) {
        currentLang = settingsDataStore.selectedLanguage.first()
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false }, lang = currentLang)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121316))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (currentLang == "ru") "Настройки системы" else "Налаштування системи",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                SettingButtonItem(
                    icon = Icons.Rounded.CloudUpload,
                    title = if (currentLang == "ru") "Экспорт логов безопасности" else "Експорт логів безпеки",
                    onClick = {
                        Log.d("CONSUL_SETTINGS", "Dialog: Export logs clicked")
                        scope.launch {
                            val result = backupLogs(context)
                            if (result.startsWith("/")) {
                                Log.d("CONSUL_SETTINGS", "Dialog: Logs exported to $result")
                                Toast.makeText(context, if (currentLang == "ru") "Логи сохранены в Загрузки" else "Логи збережено в Завантаження", Toast.LENGTH_LONG).show()
                            } else {
                                Log.e("CONSUL_SETTINGS", "Dialog: Export error: $result")
                                Toast.makeText(context, "Error: $result", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )

                val cleanLogsOnImei by settingsDataStore.cleanLogsOnImeiChange.collectAsStateWithLifecycle(initialValue = true)
                SettingSwitchItem(
                    icon = Icons.Rounded.DeleteSweep,
                    title = if (currentLang == "ru") "Очищать логи после смены IMEI" else "Очищати логи після зміни IMEI",
                    checked = cleanLogsOnImei,
                    onCheckedChange = { scope.launch { settingsDataStore.setCleanLogsOnImeiChange(it) } }
                )

                // BLE Watch Alert toggle
                val wearBleEnabled by settingsDataStore.wearBleAlertEnabled.collectAsStateWithLifecycle(initialValue = false)
                SettingSwitchItem(
                    icon = Icons.Rounded.Watch,
                    title = if (currentLang == "ru") "Оповещение на часы (BLE)" else "Сповіщення на годинник (BLE)",
                    checked = wearBleEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsDataStore.setWearBleAlertEnabled(enabled)
                            if (enabled) {
                                com.android.imeisettings.service.WearAlertService.start(context)
                            } else {
                                com.android.imeisettings.service.WearAlertService.stop()
                            }
                        }
                    }
                )
                if (wearBleEnabled) {
                    val devCount = com.android.imeisettings.service.WearAlertService.getSubscribedDeviceCount()
                    Text(
                        text = if (currentLang == "ru")
                            "   BLE активен · Подключено устройств: $devCount"
                        else
                            "   BLE активний · Підключено пристроїв: $devCount",
                        fontSize = 11.sp,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, bottom = 4.dp)
                    )
                }

                // Learning Mode Section
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(16.dp))

                val learningActive by settingsDataStore.learningModeActive.collectAsStateWithLifecycle(initialValue = false)
                val learningEndTime by settingsDataStore.learningModeEndTime.collectAsStateWithLifecycle(initialValue = 0L)

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (learningActive) Color(0xFF003333) else Color(0xFF1E1E24)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Psychology, 
                                contentDescription = null, 
                                tint = if (learningActive) Color.Cyan else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (currentLang == "ru") "Режим обучения" else "Режим навчання",
                                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (currentLang == "ru") "Авто-наполнение белого списка на 1 час." else "Авто-наповнення білого списку на 1 годину.",
                                    color = Color.Gray, fontSize = 12.sp
                                )
                            }
                        }
                        
                        if (learningActive) {
                            val remaining = ((learningEndTime - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                            Text(
                                text = if (currentLang == "ru") "Активно еще ${remaining}мин" else "Активно ще ${remaining}хв",
                                color = Color.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        
                        Button(
                            onClick = { scope.launch { settingsDataStore.setLearningMode(!learningActive) } },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (learningActive) Color.Red.copy(alpha = 0.6f) else Color(0xFF4CAF50)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (learningActive) 
                                    (if (currentLang == "ru") "ОСТАНОВИТЬ" else "ЗУПИНИТИ")
                                else 
                                    (if (currentLang == "ru") "НАЧАТЬ ОБУЧЕНИЕ" else "ПОЧАТИ НАВЧАННЯ"),
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(8.dp))

                SettingButtonItem(
                    icon = Icons.Rounded.Info,
                    title = if (currentLang == "ru") "О программе" else "Про програму",
                    onClick = {
                        Log.d("CONSUL_SETTINGS", "Dialog: About clicked")
                        showAbout = true
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        Log.d("CONSUL_SETTINGS", "Dialog: Close clicked")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB0C4FF))
                ) {
                    Text(
                        text = if (currentLang == "ru") "ЗАКРЫТЬ" else "ЗАКРИТИ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
fun SettingButtonItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun SettingSwitchItem(icon: ImageVector, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFB0C4FF), checkedTrackColor = Color(0xFFB0C4FF).copy(alpha = 0.5f))
        )
    }
}

private suspend fun backupLogs(context: Context): String = withContext(Dispatchers.IO) {
    try {
        val db = AppDatabase.getDatabase(context)
        val logs = db.securityLogDao().getAllLogs().first()
        if (logs.isEmpty()) return@withContext "No logs found"

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Consul_SecurityLogs_$timeStamp.csv"

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }

        val uri = resolver.insert(collection, contentValues) ?: return@withContext "Failed to create file record"

        resolver.openOutputStream(uri)?.use { os ->
            BufferedWriter(OutputStreamWriter(os)).use { writer ->
                writer.append("ID,Timestamp,Type,Message,PDU\n")
                logs.forEach { log ->
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                    val safeMsg = log.message.replace(",", " |")
                    writer.append("${log.id},$date,${log.type},$safeMsg,${log.pdu ?: ""}\n")
                }
                writer.flush()
            }
        }
        
        Log.i("CONSUL_DEBUG", "Logs exported successfully: $fileName")
        return@withContext "/sdcard/Download/$fileName"
    } catch (e: Exception) {
        Log.e("CONSUL_DEBUG", "Export Error: ${e.message}")
        return@withContext e.message ?: "Unknown error"
    }
}
