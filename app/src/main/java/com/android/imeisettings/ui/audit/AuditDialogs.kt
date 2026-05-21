package com.android.imeisettings.ui.audit

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.imeisettings.util.OemRilUtil
import com.android.imeisettings.util.RootUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtTerminalDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var command by remember { mutableStateOf("AT") }
    val logs = remember { mutableStateListOf<String>() }
    var selectedSlot by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val quickCommands = listOf(
        "AT" to "Check Link",
        "AT+CGMR" to "Version",
        "AT+EGMR=0,7" to "Get IMEI 1",
        "AT+EGMR=0,10" to "Get IMEI 2",
        "AT+CSQ" to "Signal",
        "help" to "HELP"
    )

    fun executeCommand(cmd: String) {
        val time = timeFormat.format(Date())
        if (cmd.lowercase() == "help") {
            logs.add("[$time] >> help")
            logs.add("--- ТЕРМІНАЛ CONSUL IMEI ---")
            logs.add("Команди для роботи з модемом:")
            logs.add("1. AT - Перевірка зв'язку")
            logs.add("2. AT+CGMR - Версія прошивки")
            logs.add("3. AT+EGMR=0,7 - Поточний IMEI SIM1")
            logs.add("4. AT+EGMR=0,10 - Поточний IMEI SIM2")
            logs.add("5. AT+CSQ - Рівень сигналу")
            logs.add("6. AT+EAIC=2 - Синхронізація NVRAM")
            logs.add("7. AT+CFUN=1,1 - Перезавантаження модему")
            logs.add("---------------------------")
            return
        }

        scope.launch {
            logs.add("[$time] >> $cmd")
            val result = withContext(Dispatchers.IO) { OemRilUtil.sendAtCommand(context, cmd, selectedSlot) }
            logs.add("[$time] << $result")
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F22))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("AT-Terminal (System UID)", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { selectedSlot = 0 },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectedSlot == 0) Color(0xFF7C8CCF) else Color(0xFF2A2A2F))
                    ) { Text("SIM 1", color = if (selectedSlot == 0) Color.Black else Color.White) }
                    
                    Button(
                        onClick = { selectedSlot = 1 },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectedSlot == 1) Color(0xFF7C8CCF) else Color(0xFF2A2A2F))
                    ) { Text("SIM 2", color = if (selectedSlot == 1) Color.Black else Color.White) }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = Color.Black,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.DarkGray)
                ) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(logs) { log ->
                            Text(text = log, color = if (log.contains(">>")) Color.Cyan else Color.Green, 
                                 fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickCommands) { (cmd, label) ->
                        SuggestionChip(
                            onClick = { 
                                if (cmd == "help") executeCommand("help") else command = cmd 
                            },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(labelColor = Color.LightGray)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7C8CCF),
                        unfocusedBorderColor = Color.Gray
                    ),
                    trailingIcon = {
                        IconButton(onClick = { executeCommand(command) }) {
                            Icon(imageVector = Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = Color(0xFF7C8CCF))
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("ЗАКРИТИ", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun ModemFingerprintDialog(onDismiss: () -> Unit) {
    var deviceInfo by remember { mutableStateOf("Збір даних...") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            val baseband = withContext(Dispatchers.IO) { RootUtil.executeWithOutput("getprop gsm.version.baseband") }
            val ril = withContext(Dispatchers.IO) { RootUtil.executeWithOutput("getprop gsm.version.ril-impl") }
            val selinux = withContext(Dispatchers.IO) { RootUtil.executeWithOutput("getenforce") }
            val hw = Build.HARDWARE
            val fp = Build.FINGERPRINT

            deviceInfo = """
                [Baseband Version]
                $baseband
                
                [RIL Implementation]
                $ril
                
                [Hardware]
                $hw
                
                [SELinux Status]
                $selinux
                
                [OS Fingerprint]
                $fp
            """.trimIndent()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F22))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Modem Fingerprint Audit", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(
                    color = Color.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = deviceInfo,
                        color = Color.Yellow,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("ЗАКРИТИ")
                }
            }
        }
    }
}

@Composable
fun WifiIdsDialog(context: Context, onDismiss: () -> Unit) {
    var wifiInfo by remember { mutableStateOf("Сканування Wi-Fi мережі...") }

    LaunchedEffect(Unit) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            
            if (info.networkId == -1) {
                wifiInfo = "Wi-Fi вимкнено або не підключено до мережі."
            } else {
                val ssid = info.ssid
                val bssid = info.bssid ?: "Приховано"
                val mac = info.macAddress ?: "Невідомо"
                val linkSpeed = info.linkSpeed
                val freq = info.frequency

                wifiInfo = """
                    [Поточне підключення]
                    SSID: $ssid
                    BSSID (MAC роутера): $bssid
                    Мій MAC: $mac
                    Швидкість: $linkSpeed Mbps
                    Частота: $freq MHz
                    
                    Стан безпеки: 
                    ${if (bssid.contains("00:00:00") || ssid.contains("Free")) "⚠️ ПІДОЗРІЛА (Відкрита мережа/Spoofing)" else "✅ ЗАХИЩЕНА"}
                """.trimIndent()
            }
        } catch (e: SecurityException) {
            wifiInfo = "Помилка доступу до Wi-Fi. Немає дозволів на локацію."
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F22))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("WI-FI IDS Audit", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = wifiInfo,
                        color = Color.Cyan,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("ЗАКРИТИ")
                }
            }
        }
    }
}
