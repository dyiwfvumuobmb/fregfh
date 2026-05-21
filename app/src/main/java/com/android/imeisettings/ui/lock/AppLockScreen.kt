package com.android.imeisettings.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.android.imeisettings.data.local.SettingsDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val trans = mapOf(
    "en" to mapOf(
        "title" to "Enter PIN", "subtitle" to "Enter your PIN to unlock",
        "wrong" to "Wrong PIN", "biometric" to "Use Fingerprint",
        "bio_title" to "Unlock CONSUL IMEI", "bio_subtitle" to "Use biometrics to unlock"
    ),
    "uk" to mapOf(
        "title" to "Введіть PIN", "subtitle" to "Введіть PIN для розблокування",
        "wrong" to "Невірний PIN", "biometric" to "Відбиток пальця",
        "bio_title" to "Розблокувати CONSUL IMEI", "bio_subtitle" to "Біометрія для розблокування"
    ),
    "ru" to mapOf(
        "title" to "Введите PIN", "subtitle" to "Введите PIN для разблокировки",
        "wrong" to "Неверный PIN", "biometric" to "Отпечаток пальца",
        "bio_title" to "Разблокировать CONSUL IMEI", "bio_subtitle" to "Биометрия для разблокировки"
    ),
    "de" to mapOf(
        "title" to "PIN eingeben", "subtitle" to "PIN zum Entsperren eingeben",
        "wrong" to "Falscher PIN", "biometric" to "Fingerabdruck",
        "bio_title" to "CONSUL IMEI entsperren", "bio_subtitle" to "Biometrie zum Entsperren"
    ),
    "pl" to mapOf(
        "title" to "Wpisz PIN", "subtitle" to "Wpisz PIN aby odblokować",
        "wrong" to "Zły PIN", "biometric" to "Odcisk palca",
        "bio_title" to "Odblokuj CONSUL IMEI", "bio_subtitle" to "Biometria do odblokowania"
    ),
    "lt" to mapOf(
        "title" to "Įveskite PIN", "subtitle" to "Įveskite PIN atrakinimui",
        "wrong" to "Neteisingas PIN", "biometric" to "Piršto atspaudas",
        "bio_title" to "Atrakinti CONSUL IMEI", "bio_subtitle" to "Biometrika atrakinimui"
    ),
    "lv" to mapOf(
        "title" to "Ievadiet PIN", "subtitle" to "Ievadiet PIN atbloķēšanai",
        "wrong" to "Nepareizs PIN", "biometric" to "Pirkstu nospiedums",
        "bio_title" to "Atbloķēt CONSUL IMEI", "bio_subtitle" to "Biometrika atbloķēšanai"
    ),
    "es" to mapOf(
        "title" to "Ingrese PIN", "subtitle" to "Ingrese PIN para desbloquear",
        "wrong" to "PIN incorrecto", "biometric" to "Huella digital",
        "bio_title" to "Desbloquear CONSUL IMEI", "bio_subtitle" to "Biometría para desbloquear"
    )
)

@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsDataStore.getInstance(context) }
    val lang by settings.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
    val savedPin by settings.appLockPin.collectAsStateWithLifecycle(initialValue = "")
    val useBiometric by settings.useBiometric.collectAsStateWithLifecycle(initialValue = true)
    val s = trans[lang] ?: trans["en"]!!

    var enteredPin by remember { mutableStateOf("") }
    var wrongPin by remember { mutableStateOf(false) }

    val biometricAvailable = remember {
        val mgr = BiometricManager.from(context)
        mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    LaunchedEffect(useBiometric, biometricAvailable) {
        if (useBiometric && biometricAvailable) {
            showBiometricPrompt(context, s, onUnlocked)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121216)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Lock, null, tint = Color(0xFF7C8CCF), modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(s["title"]!!, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(s["subtitle"]!!, color = Color.Gray, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(24.dp))

        // PIN dots
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { index ->
                Surface(
                    shape = CircleShape,
                    color = if (index < enteredPin.length) {
                        if (wrongPin) Color(0xFFF44336) else Color(0xFF7C8CCF)
                    } else Color(0xFF2A2A32),
                    modifier = Modifier.size(16.dp)
                ) {}
            }
        }

        if (wrongPin) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(s["wrong"]!!, color = Color(0xFFF44336), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Numeric keypad
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "⌫")
        )

        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(modifier = Modifier.size(64.dp))
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1E1E24),
                            modifier = Modifier.size(64.dp),
                            onClick = {
                                wrongPin = false
                                if (key == "⌫") {
                                    if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                } else if (enteredPin.length < 4) {
                                    enteredPin += key
                                    if (enteredPin.length == 4) {
                                        if (savedPin.isEmpty() || settings.verifyPin(enteredPin, savedPin)) {
                                            onUnlocked()
                                        } else {
                                            wrongPin = true
                                            enteredPin = ""
                                        }
                                    }
                                }
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                if (key == "⌫") {
                                    Icon(Icons.AutoMirrored.Rounded.Backspace, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                } else {
                                    Text(key, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (biometricAvailable && useBiometric) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = { showBiometricPrompt(context, s, onUnlocked) }) {
                Icon(Icons.Rounded.Fingerprint, null, tint = Color(0xFF7C8CCF), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(s["biometric"]!!, color = Color(0xFF7C8CCF))
            }
        }
    }
}

private fun showBiometricPrompt(context: Context, s: Map<String, String>, onUnlocked: () -> Unit) {
    val activity = context as? FragmentActivity ?: return
    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onUnlocked()
        }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(s["bio_title"] ?: "Unlock")
        .setSubtitle(s["bio_subtitle"] ?: "Use biometrics")
        .setNegativeButtonText("PIN")
        .build()
    try { prompt.authenticate(info) } catch (_: Exception) {}
}
