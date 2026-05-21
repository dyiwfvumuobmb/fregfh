package com.android.imeisettings.ui.settings

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.service.ImeiRotationWorker
import com.android.imeisettings.util.BackupManager
import kotlinx.coroutines.launch

private val trans = mapOf(
    "en" to mapOf(
        "title" to "Settings",
        "cat_app" to "Application",
        "language" to "Language",
        "cat_imei" to "IMEI / Hardware",
        "auto_reboot" to "Auto Reboot",
        "auto_reboot_desc" to "Reboot immediately after IMEI change",
        "prevent_repeats" to "Prevent Repeats",
        "prevent_repeats_desc" to "Do not allow same IMEI twice",
        "clean_on_change" to "Privacy Purge",
        "clean_on_change_desc" to "Clear security logs after IMEI change",
        "cat_security" to "Security Service",
        "kill_switch" to "Panic Kill-Switch",
        "kill_switch_desc" to "Disconnect network if attack detected",
        "radio_monitor" to "Radio Log Monitor",
        "radio_monitor_desc" to "Deep analysis of modem logs",
        "stealth" to "Stealth Mode",
        "stealth_desc" to "Hide app from recent tasks list",
        "clean_logs" to "Auto-Clean Logs",
        "clean_logs_desc" to "Delete logs older than 7 days",
        "app_lock" to "App Lock",
        "app_lock_desc" to "Require PIN to open app",
        "biometric" to "Biometric Unlock",
        "biometric_desc" to "Use fingerprint or face ID",
        "cat_rotation" to "Identity Rotation",
        "auto_rotate" to "Scheduled Rotation",
        "auto_rotate_desc" to "Change IMEI automatically",
        "rotate_interval" to "Rotation Interval",
        "rotate_network" to "On Network Change",
        "rotate_network_desc" to "Rotate identifiers when SIM changes",
        "cat_learning" to "Learning Mode",
        "learning_title" to "Auto-build Whitelist",
        "learning_desc" to "Collects all nearby towers into safe zones for 1 hour.",
        "learning_start" to "START LEARNING",
        "learning_stop" to "STOP",
        "learning_active" to "Active for %sm",
        "cat_extra" to "Maintenance",
        "export_logs" to "Export Security Logs",
        "about" to "About Application",
        "pin_title" to "Set PIN Code",
        "pin_confirm" to "Confirm PIN",
        "pin_mismatch" to "PINs do not match",
        "pin_set" to "SET",
        "pin_cancel" to "Cancel"
    ),
    "uk" to mapOf(
        "title" to "Налаштування",
        "cat_app" to "Додаток",
        "language" to "Мова",
        "cat_imei" to "IMEI / Обладнання",
        "auto_reboot" to "Авто-перезавантаження",
        "auto_reboot_desc" to "Перезавантажити після зміни IMEI",
        "prevent_repeats" to "Запобігати повторам",
        "prevent_repeats_desc" to "Не допускати однакових IMEI",
        "clean_on_change" to "Очищення при зміні",
        "clean_on_change_desc" to "Видаляти логи після зміни IMEI",
        "cat_security" to "Служба безпеки",
        "kill_switch" to "Kill-Switch паніки",
        "kill_switch_desc" to "Вимкнути мережу при атаці",
        "radio_monitor" to "Моніторинг радіо-логів",
        "radio_monitor_desc" to "Глибокий аналіз логів модему",
        "stealth" to "Режим стелс",
        "stealth_desc" to "Приховати додаток зі списку завдань",
        "clean_logs" to "Авто-очищення логів",
        "clean_logs_desc" to "Видаляти логи старіше 7 днів",
        "app_lock" to "Блокування додатку",
        "app_lock_desc" to "Запитувати PIN при вході",
        "biometric" to "Біометричний вхід",
        "biometric_desc" to "Використовувати відбиток пальця",
        "cat_rotation" to "Ротація особистості",
        "auto_rotate" to "Планова ротація",
        "auto_rotate_desc" to "Змінювати IMEI автоматично",
        "rotate_interval" to "Інтервал ротації",
        "rotate_network" to "При зміні мережі",
        "rotate_network_desc" to "Змінювати при переключенні SIM",
        "cat_learning" to "Режим навчання",
        "learning_title" to "Авто-наповнення білого списку",
        "learning_desc" to "Протягом години всі вишки будуть додані в безпечні зони.",
        "learning_start" to "ПОЧАТИ НАВЧАННЯ",
        "learning_stop" to "ЗУПИНИТИ",
        "learning_active" to "Активно ще %sхв",
        "cat_extra" to "Обслуговування",
        "export_logs" to "Експорт логів безпеки",
        "about" to "Про програму",
        "pin_title" to "Встановити PIN",
        "pin_confirm" to "Підтвердити PIN",
        "pin_mismatch" to "PIN не збігаються",
        "pin_set" to "ОК",
        "pin_cancel" to "Скасувати"
    ),
    "ru" to mapOf(
        "title" to "Настройки",
        "cat_app" to "Приложение",
        "language" to "Язык",
        "cat_imei" to "IMEI / Оборудование",
        "auto_reboot" to "Авто-перезагрузка",
        "auto_reboot_desc" to "Перезагружать после смены IMEI",
        "prevent_repeats" to "Предотвращать повторы",
        "prevent_repeats_desc" to "Не допускать одинаковых IMEI",
        "clean_on_change" to "Очистка при смене",
        "clean_on_change_desc" to "Удалять логи после смены IMEI",
        "cat_security" to "Служба безопасности",
        "kill_switch" to "Panic Kill-Switch",
        "kill_switch_desc" to "Отключить сеть при атаке",
        "radio_monitor" to "Мониторинг радио-логов",
        "radio_monitor_desc" to "Глубокий анализ логов модема",
        "stealth" to "Режим стелс",
        "stealth_desc" to "Скрыть приложение из списка задач",
        "clean_logs" to "Авто-очистка логов",
        "clean_logs_desc" to "Удалять логи старше 7 дней",
        "app_lock" to "Блокировка приложения",
        "app_lock_desc" to "Запрашивать PIN при входе",
        "biometric" to "Биометрический вход",
        "biometric_desc" to "Использовать отпечаток пальца",
        "cat_rotation" to "Ротация личности",
        "auto_rotate" to "Плановая ротация",
        "auto_rotate_desc" to "Менять IMEI автоматически",
        "rotate_interval" to "Интервал ротации",
        "rotate_network" to "При смене сети",
        "rotate_network_desc" to "Менять при переключении SIM",
        "cat_learning" to "Режим обучения",
        "learning_title" to "Авто-наполнение белого списка",
        "learning_desc" to "В течение часа все вышки будут добавлены в безопасные зоны.",
        "learning_start" to "НАЧАТЬ ОБУЧЕНИЕ",
        "learning_stop" to "ОСТАНОВИТЬ",
        "learning_active" to "Активно еще %sмин",
        "cat_extra" to "Обслуживание",
        "export_logs" to "Экспорт логов безопасности",
        "about" to "О программе",
        "pin_title" to "Установить PIN",
        "pin_confirm" to "Подтвердите PIN",
        "pin_mismatch" to "PIN не совпадают",
        "pin_set" to "ОК",
        "pin_cancel" to "Отмена"
    ),
    "de" to mapOf(
        "title" to "Einstellungen",
        "cat_app" to "Anwendung",
        "language" to "Sprache",
        "cat_imei" to "IMEI / Hardware",
        "auto_reboot" to "Auto-Neustart",
        "auto_reboot_desc" to "Sofort nach IMEI-Wechsel neu starten",
        "prevent_repeats" to "Wiederholungen verhindern",
        "prevent_repeats_desc" to "Gleiche IMEI nicht doppelt zulassen",
        "clean_on_change" to "Privacy-Bereinigung",
        "clean_on_change_desc" to "Sicherheitsprotokolle nach IMEI-Wechsel löschen",
        "cat_security" to "Sicherheitsdienst",
        "kill_switch" to "Panic Kill-Switch",
        "kill_switch_desc" to "Netzwerk trennen bei erkanntem Angriff",
        "radio_monitor" to "Radio Log Monitor",
        "radio_monitor_desc" to "Tiefenanalyse der Modem-Logs",
        "stealth" to "Tarnmodus",
        "stealth_desc" to "App aus der Liste der letzten Aufgaben ausblenden",
        "clean_logs" to "Auto-Log-Bereinigung",
        "clean_logs_desc" to "Logs älter als 7 Tage löschen",
        "app_lock" to "App-Sperre",
        "app_lock_desc" to "PIN zum Öffnen der App erforderlich",
        "biometric" to "Biometrisches Entsperren",
        "biometric_desc" to "Fingerabdruck oder Face ID verwenden",
        "cat_rotation" to "Identitätsrotation",
        "auto_rotate" to "Geplante Rotation",
        "auto_rotate_desc" to "IMEI automatisch ändern",
        "rotate_interval" to "Rotationsintervall",
        "rotate_network" to "Bei Netzwerkwechsel",
        "rotate_network_desc" to "IDs bei SIM-Wechsel rotieren",
        "cat_learning" to "Lernmodus",
        "learning_title" to "Whitelist automatisch aufbauen",
        "learning_desc" to "Sammelt alle Türme in der Nähe für 1 Stunde in Sicherheitszonen.",
        "learning_start" to "LERNEN STARTEN",
        "learning_stop" to "STOPP",
        "learning_active" to "Aktiv für %sm",
        "cat_extra" to "Wartung",
        "export_logs" to "Sicherheitsprotokolle exportieren",
        "about" to "Über die Anwendung",
        "pin_title" to "PIN festlegen",
        "pin_confirm" to "PIN bestätigen",
        "pin_mismatch" to "PINs stimmen nicht überein",
        "pin_set" to "OK",
        "pin_cancel" to "Abbrechen"
    ),
    "pl" to mapOf(
        "title" to "Ustawienia",
        "cat_app" to "Aplikacja",
        "language" to "Język",
        "cat_imei" to "IMEI / Sprzęt",
        "auto_reboot" to "Auto-restart",
        "auto_reboot_desc" to "Zrestartuj natychmiast po zmianie IMEI",
        "prevent_repeats" to "Zapobiegaj powtórzeniom",
        "prevent_repeats_desc" to "Nie zezwalaj na ten sam IMEI dwa razy",
        "clean_on_change" to "Czyszczenie prywatności",
        "clean_on_change_desc" to "Wyczyść logi po zmianie IMEI",
        "cat_security" to "Usługa bezpieczeństwa",
        "kill_switch" to "Panic Kill-Switch",
        "kill_switch_desc" to "Rozłącz sieć w przypadku wykrycia ataku",
        "radio_monitor" to "Monitor logów radiowych",
        "radio_monitor_desc" to "Głęboka analiza logów modemu",
        "stealth" to "Tryb ukryty",
        "stealth_desc" to "Ukryj aplikację z listy ostatnich zadań",
        "clean_logs" to "Auto-czyszczenie logów",
        "clean_logs_desc" to "Usuń logi starsze niż 7 dni",
        "app_lock" to "Blokada aplikacji",
        "app_lock_desc" to "Wymagaj PIN-u, aby otworzyć aplikację",
        "biometric" to "Odblokowanie biometryczne",
        "biometric_desc" to "Użyj odcisku palca lub Face ID",
        "cat_rotation" to "Rotacja tożsamości",
        "auto_rotate" to "Zaplanowana rotacja",
        "auto_rotate_desc" to "Zmieniaj IMEI automatycznie",
        "rotate_interval" to "Interwał rotacji",
        "rotate_network" to "Przy zmianie sieci",
        "rotate_network_desc" to "Zmieniaj identyfikatory przy zmianie SIM",
        "cat_learning" to "Tryb nauki",
        "learning_title" to "Auto-budowanie białej listy",
        "learning_desc" to "Zbiera wszystkie pobliskie wieże do bezpiecznych stref przez 1 godzinę.",
        "learning_start" to "ROZPOCZNIJ NAUKĘ",
        "learning_stop" to "STOP",
        "learning_active" to "Aktywne przez %sm",
        "cat_extra" to "Konserwacja",
        "export_logs" to "Eksportuj logi bezpieczeństwa",
        "about" to "O aplikacji",
        "pin_title" to "Ustaw PIN",
        "pin_confirm" to "Potwierdź PIN",
        "pin_mismatch" to "PINy nie pasują",
        "pin_set" to "OK",
        "pin_cancel" to "Anuluj"
    ),
    "lt" to mapOf(
        "title" to "Nustatymai",
        "cat_app" to "Programa",
        "language" to "Kalba",
        "cat_imei" to "IMEI / Įranga",
        "auto_reboot" to "Auto-perkrovimas",
        "auto_reboot_desc" to "Perkrauti iškart po IMEI pakeitimo",
        "prevent_repeats" to "Vengti pasikartojimų",
        "prevent_repeats_desc" to "Neleisti tos pačios IMEI du kartus",
        "clean_on_change" to "Privatumo valymas",
        "clean_on_change_desc" to "Išvalyti saugumo žurnalus po IMEI pakeitimo",
        "cat_security" to "Saugumo paslauga",
        "kill_switch" to "Panic Kill-Switch",
        "kill_switch_desc" to "Atjungti tinklą aptikus ataką",
        "radio_monitor" to "Radijo žurnalų monitorius",
        "radio_monitor_desc" to "Gilioji modemo žurnalų analizė",
        "stealth" to "Stealth režimas",
        "stealth_desc" to "Paslėpti programą iš paskutinių užduočių sąrašo",
        "clean_logs" to "Auto-logų valymas",
        "clean_logs_desc" to "Ištrinti žurnalus senesnius nei 7 dienos",
        "app_lock" to "Programos užraktas",
        "app_lock_desc" to "Reikalauti PIN kodo programai atidaryti",
        "biometric" to "Biometrinis atrakinimas",
        "biometric_desc" to "Naudoti piršto atspaudą arba Face ID",
        "cat_rotation" to "Tapatybės rotacija",
        "auto_rotate" to "Suplanuota rotacija",
        "auto_rotate_desc" to "Keisti IMEI automatiškai",
        "rotate_interval" to "Rotacijos intervalas",
        "rotate_network" to "Pasikeitus tinklui",
        "rotate_network_desc" to "Keisti identifikatorius pasikeitus SIM kortelei",
        "cat_learning" to "Mokymosi režimas",
        "learning_title" to "Auto-baltasis sąrašas",
        "learning_desc" to "1 valandą renka visus netoliese esančius bokštus į saugias zonas.",
        "learning_start" to "PRADĖTI MOKYMĄSĮ",
        "learning_stop" to "STABDYTI",
        "learning_active" to "Aktyvu dar %sm",
        "cat_extra" to "Priežiūra",
        "export_logs" to "Eksportuoti saugumo žurnalus",
        "about" to "Apie programą",
        "pin_title" to "Nustatyti PIN",
        "pin_confirm" to "Patvirtinti PIN",
        "pin_mismatch" to "PIN nesutampa",
        "pin_set" to "OK",
        "pin_cancel" to "Atšaukti"
    ),
    "lv" to mapOf(
        "title" to "Iestatījumi",
        "cat_app" to "Lietotne",
        "language" to "Valoda",
        "cat_imei" to "IMEI / Aparatūra",
        "auto_reboot" to "Auto-restartēšana",
        "auto_reboot_desc" to "Restartēt tūlīt pēc IMEI maiņas",
        "prevent_repeats" to "Novērst atkārtošanos",
        "prevent_repeats_desc" to "Neatļaut vienu un to pašu IMEI divreiz",
        "clean_on_change" to "Privātuma tīrīšana",
        "clean_on_change_desc" to "Dzēst drošības žurnālus pēc IMEI maiņas",
        "cat_security" to "Drošības dienests",
        "kill_switch" to "Panic Kill-Switch",
        "kill_switch_desc" to "Atvienot tīklu, ja tiek konstatēts uzbrukums",
        "radio_monitor" to "Radio žurnālu monitors",
        "radio_monitor_desc" to "Dziļā modema žurnālu analīze",
        "stealth" to "Stealth režīms",
        "stealth_desc" to "Paslėpt lietotni no pēdējo uzdevumu saraksta",
        "clean_logs" to "Auto-žurnālu tīrīšana",
        "clean_logs_desc" to "Dzēst žurnālus, kas vecāki par 7 dienām",
        "app_lock" to "Lietotnes bloķēšana",
        "app_lock_desc" to "Pieprasīt PIN kodu lietotnes atvēršanai",
        "biometric" to "Biometriskā atbloķēšana",
        "biometric_desc" to "Izmantot pirkstu nospiedumu vai Face ID",
        "cat_rotation" to "Identitātes rotācija",
        "auto_rotate" to "Plānotā rotācija",
        "auto_rotate_desc" to "Mainīt IMEI automātiski",
        "rotate_interval" to "Rotacijos intervalas",
        "rotate_network" to "Mainoties tīklam",
        "rotate_network_desc" to "Mainīt identifikatorus, mainot SIM karti",
        "cat_learning" to "Mācīšanās režīms",
        "learning_title" to "Auto-baltais saraksts",
        "learning_desc" to "Vāc visus tuvumā esošos torņus drošajās zonās uz 1 stundu.",
        "learning_start" to "SĀKT MĀCĪŠANOS",
        "learning_stop" to "STOP",
        "learning_active" to "Aktīvs vēl %sm",
        "cat_extra" to "Apkope",
        "export_logs" to "Eksportēt drošības žurnālus",
        "about" to "Par lietotni",
        "pin_title" to "Iestatīt PIN",
        "pin_confirm" to "Apstiprināt PIN",
        "pin_mismatch" to "PIN nesakrīt",
        "pin_set" to "OK",
        "pin_cancel" to "Atcelt"
    ),
    "es" to mapOf(
        "title" to "Ajustes",
        "cat_app" to "Aplicación",
        "language" to "Idioma",
        "cat_imei" to "IMEI / Hardware",
        "auto_reboot" to "Auto-reinicio",
        "auto_reboot_desc" to "Reiniciar inmediatamente después del cambio de IMEI",
        "prevent_repeats" to "Prevenir repeticiones",
        "prevent_repeats_desc" to "No permitir el mismo IMEI dos veces",
        "clean_on_change" to "Purga de privacidad",
        "clean_on_change_desc" to "Limpiar registros de seguridad después del cambio de IMEI",
        "cat_security" to "Servicio de seguridad",
        "kill_switch" to "Panic Kill-Switch",
        "kill_switch_desc" to "Desconectar la red si se detecta un ataque",
        "radio_monitor" to "Monitor de registros de radio",
        "radio_monitor_desc" to "Análisis profundo de los registros del módem",
        "stealth" to "Modo Stealth",
        "stealth_desc" to "Ocultar la aplicación de la lista de tareas recientes",
        "clean_logs" to "Auto-limpieza de registros",
        "clean_logs_desc" to "Eliminar registros con más de 7 días",
        "app_lock" to "Bloqueo de aplicación",
        "app_lock_desc" to "Requerir PIN para abrir la aplicación",
        "biometric" to "Desbloqueo biométrico",
        "biometric_desc" to "Usar huella digital o Face ID",
        "cat_rotation" to "Rotación de identidad",
        "auto_rotate" to "Rotación programada",
        "auto_rotate_desc" to "Cambiar IMEI automáticamente",
        "rotate_interval" to "Intervalo de rotación",
        "rotate_network" to "Al cambiar de red",
        "rotate_network_desc" to "Rotar identificadores cuando cambie la SIM",
        "cat_learning" to "Modo aprendizaje",
        "learning_title" to "Auto-construir lista blanca",
        "learning_desc" to "Recopila todas las torres cercanas en zonas seguras durante 1 hora.",
        "learning_start" to "INICIAR APRENDIZAJE",
        "learning_stop" to "DETENER",
        "learning_active" to "Activo por %sm",
        "cat_extra" to "Mantenimiento",
        "export_logs" to "Exportar registros de seguridad",
        "about" to "Acerca de la aplicación",
        "pin_title" to "Establecer PIN",
        "pin_confirm" to "Confirmar PIN",
        "pin_mismatch" to "Los PIN no coinciden",
        "pin_set" to "OK",
        "pin_cancel" to "Cancelar"
    )
)

private val languages = listOf(
    "en" to "English",
    "uk" to "Українська",
    "ru" to "Русский",
    "de" to "Deutsch",
    "pl" to "Polski",
    "lt" to "Lietuvių",
    "lv" to "Latviešu",
    "es" to "Español"
)

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsDataStore.getInstance(context) }
    val lang by settings.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
    val s = trans[lang] ?: trans["en"]!!

    val autoReboot by settings.autoRebootAfterImei.collectAsStateWithLifecycle(initialValue = true)
    val preventRepeats by settings.preventRepeats.collectAsStateWithLifecycle(initialValue = false)
    val autoClean by settings.autoCleanLogs.collectAsStateWithLifecycle(initialValue = false)
    val cleanOnChange by settings.cleanLogsOnImeiChange.collectAsStateWithLifecycle(initialValue = true)
    val killSwitch by settings.killSwitchEnabled.collectAsStateWithLifecycle(initialValue = true)
    val radioMonitor by settings.radioLogMonitorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val stealth by settings.stealthMode.collectAsStateWithLifecycle(initialValue = false)
    val appLock by settings.appLockEnabled.collectAsStateWithLifecycle(initialValue = false)
    val autoRotate by settings.autoRotateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val rotateInterval by settings.autoRotateIntervalHours.collectAsStateWithLifecycle(initialValue = 24)
    val rotateOnNetwork by settings.autoRotateOnNetworkChange.collectAsStateWithLifecycle(initialValue = false)
    
    val learningActive by settings.learningModeActive.collectAsStateWithLifecycle(initialValue = false)
    val learningEndTime by settings.learningModeEndTime.collectAsStateWithLifecycle(initialValue = 0L)

    val savedPin by settings.appLockPin.collectAsStateWithLifecycle(initialValue = "")

    var showLangDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false }, lang = lang)
    }

    if (showPinDialog) {
        PinSetupDialog(
            s = s,
            onPinSet = { pin ->
                scope.launch {
                    settings.setAppLockPin(pin)
                    settings.setAppLockEnabled(true)
                    settings.setUseBiometric(true)
                }
                showPinDialog = false
            },
            onDismiss = { showPinDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121216))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                Log.d("CONSUL_SETTINGS", "Navigate back clicked")
                onNavigateBack()
            }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White)
            }
            Text(s["title"]!!, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Language
            item { CategoryHeader(s["cat_app"]!!) }
            item {
                SettingsClickItem(s["language"]!!, languages.find { it.first == lang }?.second ?: lang, Icons.Rounded.Language) {
                    Log.d("CONSUL_SETTINGS", "Language dialog requested")
                    showLangDialog = true
                }
            }

            // IMEI
            item { CategoryHeader(s["cat_imei"]!!) }
            item { SettingsToggle(s["auto_reboot"]!!, s["auto_reboot_desc"]!!, Icons.Rounded.RestartAlt, autoReboot) { scope.launch { settings.setAutoRebootAfterImei(it) } } }
            item { SettingsToggle(s["prevent_repeats"]!!, s["prevent_repeats_desc"]!!, Icons.Rounded.Block, preventRepeats) { scope.launch { settings.setPreventRepeats(it) } } }
            item { SettingsToggle(s["clean_on_change"]!!, s["clean_on_change_desc"]!!, Icons.Rounded.CleaningServices, cleanOnChange) { scope.launch { settings.setCleanLogsOnImeiChange(it) } } }

            // Security
            item { CategoryHeader(s["cat_security"]!!) }
            item { SettingsToggle(s["kill_switch"]!!, s["kill_switch_desc"]!!, Icons.Rounded.PowerSettingsNew, killSwitch) { scope.launch { settings.setKillSwitchEnabled(it) } } }
            item { SettingsToggle(s["radio_monitor"]!!, s["radio_monitor_desc"]!!, Icons.Rounded.CellTower, radioMonitor) { scope.launch { settings.setRadioLogMonitorEnabled(it) } } }
            item { SettingsToggle(s["stealth"]!!, s["stealth_desc"]!!, Icons.Rounded.VisibilityOff, stealth) {
                scope.launch {
                    settings.setStealthMode(it)
                    // Immediately apply stealth mode
                    try {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        am.appTasks.forEach { task -> task.setExcludeFromRecents(it) }
                    } catch (e: Exception) {
                        Log.e("CONSUL_SETTINGS", "Stealth mode error: ${e.message}")
                    }
                }
            } }
            item { SettingsToggle(s["clean_logs"]!!, s["clean_logs_desc"]!!, Icons.Rounded.DeleteSweep, autoClean) { scope.launch { settings.setAutoCleanLogs(it) } } }
            item { SettingsToggle(s["app_lock"]!!, s["app_lock_desc"]!!, Icons.Rounded.Lock, appLock) {
                if (it && savedPin.isEmpty()) {
                    showPinDialog = true
                } else {
                    scope.launch {
                        settings.setAppLockEnabled(it)
                        if (it) settings.setUseBiometric(true)
                    }
                }
            } }

            // Learning Mode
            item { 
                Spacer(modifier = Modifier.height(16.dp))
                CategoryHeader(s["cat_learning"]!!) 
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = if (learningActive) Color(0xFF003333) else Color(0xFF1E1E24)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Psychology, 
                                contentDescription = null, 
                                tint = if (learningActive) Color.Cyan else Color.Gray
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s["learning_title"]!!, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(s["learning_desc"]!!, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                        
                        if (learningActive) {
                            val remaining = ((learningEndTime - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                            Text(
                                text = s["learning_active"]!!.replace("%s", remaining.toString()),
                                color = Color.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        
                        Button(
                            onClick = { scope.launch { settings.setLearningMode(!learningActive) } },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (learningActive) Color.Red.copy(alpha = 0.6f) else Color(0xFF4CAF50)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (learningActive) s["learning_stop"]!! else s["learning_start"]!!, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // Auto-rotation
            item { 
                Spacer(modifier = Modifier.height(16.dp))
                CategoryHeader(s["cat_rotation"]!!) 
            }
            item { SettingsToggle(s["auto_rotate"]!!, s["auto_rotate_desc"]!!, Icons.Rounded.Autorenew, autoRotate) {
                scope.launch {
                    settings.setAutoRotateEnabled(it)
                    if (it) {
                        ImeiRotationWorker.schedule(context, rotateInterval)
                        val msg = when (lang) {
                            "ru" -> "IMEI будет меняться каждые ${rotateInterval}ч"
                            "uk" -> "IMEI змінюватиметься кожні ${rotateInterval}г"
                            else -> "IMEI will rotate every ${rotateInterval}h"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    } else {
                        ImeiRotationWorker.cancel(context)
                    }
                }
            } }
            item {
                SettingsClickItem(s["rotate_interval"]!!, "${rotateInterval}h", Icons.Rounded.Timer) {
                    Log.d("CONSUL_SETTINGS", "Rotation interval dialog requested")
                    showIntervalDialog = true
                }
            }
            item { SettingsToggle(s["rotate_network"]!!, s["rotate_network_desc"]!!, Icons.Rounded.Wifi, rotateOnNetwork) { scope.launch { settings.setAutoRotateOnNetworkChange(it) } } }

            // Maintenance
            item { 
                Spacer(modifier = Modifier.height(16.dp))
                CategoryHeader(s["cat_extra"]!!) 
            }
            item {
                SettingsClickItem(s["export_logs"]!!, "", Icons.Rounded.CloudUpload) {
                    Log.d("CONSUL_SETTINGS", "Export logs clicked")
                    scope.launch {
                        val file = BackupManager.backupLogsToText(context)
                        if (file != null) {
                            Log.d("CONSUL_SETTINGS", "Logs exported to: ${file.absolutePath}")
                            val toastMsg = when (lang) {
                                "ru" -> "Логи сохранены в Документы"
                                "uk" -> "Логи збережено в Документи"
                                "de" -> "Logs in Dokumente gespeichert"
                                "pl" -> "Logi zapisane w Dokumentach"
                                "lt" -> "Žurnalai išsaugoti Dokumentuose"
                                "lv" -> "Žurnāli saglabāti Dokumentos"
                                "es" -> "Registros guardados en Documentos"
                                else -> "Logs saved to Documents"
                            }
                            Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show()
                        } else {
                            Log.e("CONSUL_SETTINGS", "Error: Backup failed")
                            Toast.makeText(context, "Error: Backup failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            item {
                SettingsClickItem(s["about"]!!, "", Icons.Rounded.Info) {
                    Log.d("CONSUL_SETTINGS", "About clicked")
                    showAbout = true
                }
            }
        }
    }

    if (showLangDialog) {
        AlertDialog(
            onDismissRequest = { showLangDialog = false },
            title = { Text(s["language"]!!) },
            text = {
                Column {
                    languages.forEach { (code, name) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    settings.setLanguage(code)
                                    showLangDialog = false
                                }
                            }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = code == lang, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(name, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = Color(0xFF1E1E24)
        )
    }

    if (showIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text(s["rotate_interval"]!!) },
            text = {
                Column {
                    listOf(1, 4, 8, 12, 24, 48).forEach { hours ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    settings.setAutoRotateIntervalHours(hours)
                                    if (autoRotate) {
                                        ImeiRotationWorker.schedule(context, hours)
                                    }
                                    showIntervalDialog = false
                                }
                            }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = hours == rotateInterval, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("${hours}h", color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = Color(0xFF1E1E24)
        )
    }
}

@Composable
fun CategoryHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF7C8CCF).copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 8.dp)
    )
}

@Composable
fun SettingsToggle(title: String, desc: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E1E24)
    ) {
        Row(
            modifier = Modifier.clickable { onCheckedChange(!checked) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = if (checked) Color(0xFF7C8CCF) else Color.Gray, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = Color.Gray, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF7C8CCF)))
        }
    }
}

@Composable
fun SettingsClickItem(title: String, value: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E1E24)
    ) {
        Row(
            modifier = Modifier.clickable { onClick() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF7C8CCF), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (value.isNotEmpty()) {
                    Text(value, color = Color(0xFF7C8CCF), fontSize = 13.sp)
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray)
        }
    }
}

@Composable
private fun PinSetupDialog(
    s: Map<String, String>,
    onPinSet: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var isConfirmStep by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isConfirmStep) s["pin_confirm"] ?: "Confirm PIN" else s["pin_title"] ?: "Set PIN", color = Color.White) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val currentPin = if (isConfirmStep) pin2 else pin1
                    repeat(4) { i ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (i < currentPin.length) Color(0xFF7C8CCF) else Color(0xFF2A2A32),
                            modifier = Modifier.size(16.dp)
                        ) {}
                    }
                }
                if (mismatch) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(s["pin_mismatch"] ?: "PINs do not match", color = Color(0xFFF44336), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(24.dp))
                val keys = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("","0","⌫"))
                keys.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { key ->
                            if (key.isEmpty()) {
                                Spacer(modifier = Modifier.size(56.dp))
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFF2A2A32),
                                    modifier = Modifier.size(56.dp),
                                    onClick = {
                                        mismatch = false
                                        if (key == "⌫") {
                                            if (isConfirmStep) { if (pin2.isNotEmpty()) pin2 = pin2.dropLast(1) }
                                            else { if (pin1.isNotEmpty()) pin1 = pin1.dropLast(1) }
                                        } else {
                                            if (isConfirmStep && pin2.length < 4) {
                                                pin2 += key
                                                if (pin2.length == 4) {
                                                    if (pin1 == pin2) onPinSet(pin1)
                                                    else { mismatch = true; pin2 = "" }
                                                }
                                            } else if (!isConfirmStep && pin1.length < 4) {
                                                pin1 += key
                                                if (pin1.length == 4) isConfirmStep = true
                                            }
                                        }
                                    }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        if (key == "⌫") Icon(Icons.AutoMirrored.Rounded.Backspace, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        else Text(key, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s["pin_cancel"] ?: "Cancel", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF1E1E24)
    )
}
