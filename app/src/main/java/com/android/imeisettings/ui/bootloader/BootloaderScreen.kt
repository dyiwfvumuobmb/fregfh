package com.android.imeisettings.ui.bootloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.util.BootloaderGuide
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val trans = mapOf(
    "en" to mapOf(
        "title" to "Bootloader Unlock Guide", "status" to "Bootloader Status",
        "steps" to "Steps", "warnings" to "Warnings", "back" to "Back",
        "copy" to "Copied to clipboard", "device" to "Device", "brand" to "Brand",
        "step" to "Step", "locked" to "LOCKED", "unlocked" to "UNLOCKED",
        "unknown" to "UNKNOWN", "oem_enabled" to "OEM UNLOCK ENABLED",
        "guide_hint" to "Follow these steps to unlock your bootloader"
    ),
    "uk" to mapOf(
        "title" to "Розблокування завантажувача", "status" to "Статус завантажувача",
        "steps" to "Кроки", "warnings" to "Попередження", "back" to "Назад",
        "copy" to "Скопійовано", "device" to "Пристрій", "brand" to "Бренд",
        "step" to "Крок", "locked" to "ЗАБЛОКОВАНО", "unlocked" to "РОЗБЛОКОВАНО",
        "unknown" to "НЕВІДОМО", "oem_enabled" to "OEM РОЗБЛОКОВАНО",
        "guide_hint" to "Виконайте ці кроки для розблокування завантажувача"
    ),
    "ru" to mapOf(
        "title" to "Разблокировка загрузчика", "status" to "Статус загрузчика",
        "steps" to "Шаги", "warnings" to "Предупреждения", "back" to "Назад",
        "copy" to "Скопировано", "device" to "Устройство", "brand" to "Бренд",
        "step" to "Шаг", "locked" to "ЗАБЛОКИРОВАН", "unlocked" to "РАЗБЛОКИРОВАН",
        "unknown" to "НЕИЗВЕСТНО", "oem_enabled" to "OEM ВКЛЮЧЕН",
        "guide_hint" to "Следуйте этим шагам для разблокировки загрузчика"
    ),
    "de" to mapOf(
        "title" to "Bootloader-Entsperrung", "status" to "Bootloader-Status",
        "steps" to "Schritte", "warnings" to "Warnungen", "back" to "Zurück",
        "copy" to "Kopiert", "device" to "Gerät", "brand" to "Marke",
        "step" to "Schritt", "locked" to "GESPERRT", "unlocked" to "ENTSPERRT",
        "unknown" to "UNBEKANNT", "oem_enabled" to "OEM FREIGEGEBEN",
        "guide_hint" to "Folgen Sie diesen Schritten zum Entsperren"
    ),
    "pl" to mapOf(
        "title" to "Odblokowanie bootloadera", "status" to "Status bootloadera",
        "steps" to "Kroki", "warnings" to "Ostrzeżenia", "back" to "Wstecz",
        "copy" to "Skopiowano", "device" to "Urządzenie", "brand" to "Marka",
        "step" to "Krok", "locked" to "ZABLOKOWANY", "unlocked" to "ODBLOKOWANY",
        "unknown" to "NIEZNANY", "oem_enabled" to "OEM WŁĄCZONY",
        "guide_hint" to "Wykonaj te kroki, aby odblokować bootloader"
    ),
    "lt" to mapOf(
        "title" to "Bootloader atrakinimas", "status" to "Bootloader būsena",
        "steps" to "Žingsniai", "warnings" to "Įspėjimai", "back" to "Atgal",
        "copy" to "Nukopijuota", "device" to "Įrenginys", "brand" to "Prekės ženklas",
        "step" to "Žingsnis", "locked" to "UŽRAKINTAS", "unlocked" to "ATRAKINTAS",
        "unknown" to "NEŽINOMA", "oem_enabled" to "OEM ĮJUNGTAS",
        "guide_hint" to "Sekite šiuos žingsnius, kad atrakintumėte bootloader"
    ),
    "lv" to mapOf(
        "title" to "Bootloader atbloķēšana", "status" to "Bootloader statuss",
        "steps" to "Soļi", "warnings" to "Brīdinājumi", "back" to "Atpakaļ",
        "copy" to "Nokopēts", "device" to "Ierīce", "brand" to "Zīmols",
        "step" to "Solis", "locked" to "BLOĶĒTS", "unlocked" to "ATBLOĶĒTS",
        "unknown" to "NEZINĀMS", "oem_enabled" to "OEM IESPĒJOTS",
        "guide_hint" to "Izpildiet šos soļus, lai atbloķētu bootloader"
    ),
    "es" to mapOf(
        "title" to "Desbloqueo de bootloader", "status" to "Estado del bootloader",
        "steps" to "Pasos", "warnings" to "Advertencias", "back" to "Volver",
        "copy" to "Copiado", "device" to "Dispositivo", "brand" to "Marca",
        "step" to "Paso", "locked" to "BLOQUEADO", "unlocked" to "DESBLOQUEADO",
        "unknown" to "DESCONOCIDO", "oem_enabled" to "OEM HABILITADO",
        "guide_hint" to "Siga estos pasos para desbloquear el bootloader"
    )
)

@Composable
fun BootloaderScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val settingsDataStore = remember { SettingsDataStore.getInstance(context) }
    val currentLang by settingsDataStore.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
    val s = trans[currentLang] ?: trans["en"]!!

    val bootloaderStatus = remember { BootloaderGuide.getBootloaderStatus() }
    val guide = remember { BootloaderGuide.getGuideForDevice() }

    val statusText = when (bootloaderStatus) {
        "UNLOCKED" -> s["unlocked"]!!
        "LOCKED" -> s["locked"]!!
        "OEM UNLOCK ENABLED" -> s["oem_enabled"]!!
        else -> s["unknown"]!!
    }

    val statusColor = when (bootloaderStatus) {
        "UNLOCKED" -> Color(0xFF4CAF50)
        "LOCKED" -> Color(0xFFF44336)
        "OEM UNLOCK ENABLED" -> Color(0xFFFFC107)
        else -> Color.Gray
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121216))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White)
            }
            Text(
                text = s["title"]!!,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Status card
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1E1E24)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(s["status"]!!, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (bootloaderStatus) {
                                    "UNLOCKED" -> Icons.Rounded.LockOpen
                                    "LOCKED" -> Icons.Rounded.Lock
                                    else -> Icons.AutoMirrored.Rounded.Help
                                },
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(statusText, color = statusColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${s["brand"]!!}: ${guide.brand}", color = Color.White, fontSize = 13.sp)
                        Text("${s["device"]!!}: ${guide.model}", color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            // Hint
            item {
                Text(s["guide_hint"]!!, color = Color.Gray, fontSize = 12.sp)
            }

            // Steps
            itemsIndexed(guide.steps) { index, step ->
                var expanded by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E24)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF7C8CCF).copy(alpha = 0.2f),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text("${index + 1}", color = Color(0xFF7C8CCF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                step.title,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }

                        AnimatedVisibility(visible = expanded) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Text(step.description, color = Color.Gray, fontSize = 13.sp)
                                step.command?.let { cmd ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF0D0D10),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("command", cmd))
                                                Toast.makeText(context, s["copy"]!!, Toast.LENGTH_SHORT).show()
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(cmd, color = Color(0xFF81C784), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                            Icon(Icons.Rounded.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Warnings section
            if (guide.warnings.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(s["warnings"]!!, color = Color(0xFFFFC107), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                guide.warnings.forEach { warning ->
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFC107).copy(alpha = 0.08f)
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Rounded.Warning, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(warning, color = Color(0xFFFFC107).copy(alpha = 0.8f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
