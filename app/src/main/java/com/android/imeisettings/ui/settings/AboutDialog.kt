package com.android.imeisettings.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val aboutTrans = mapOf(
    "en" to listOf(
        "CONSUL IMEI Guard Pro", "Version 2.0 | Security Complex",
        "IMEI Changer (Multi-Strategy)", "The system automatically adapts to the CPU (MTK, Qualcomm, Unisoc, Exynos). It uses NVRAM injection and direct RIL memory writing to ensure IDs persist and remain invisible to network operators.",
        "PDU Firewall (HoneyPot ACK)", "Advanced protection against Silent SMS (Type-0). When an attack is detected, the module returns a fake success code (ACK) to the network while completely blocking the malicious packet from reaching the device.",
        "Binary & OTA Protection", "Full blocking of binary SMS and OTA (Over-the-Air) commands that attempt to read SIM encryption keys (Ki/Kc) or install hidden tracker applets.",
        "Forensic Logging", "Every intercepted attack is recorded in raw HEX format. This allows security experts to analyze the signature of the attacker and the type of equipment used (IMSI Catcher).",
        "Kill Switch & Lockdown", "If more than 3 attacks are detected in a short time, the system can automatically disable the radio module to prevent triangulation and data interception.",
        "System Integrity", "Operates under UID 1000 with system-level signature. This provides the highest level of priority, allowing the app to override standard OS security policies.",
        "Legal Notice", "Warning: IMEI modification is for educational and security testing only. Use only on hardware you own. The author is not responsible for any misuse or legal violations in your jurisdiction.",
        "UNDERSTOOD"
    ),
    "uk" to listOf(
        "CONSUL IMEI Guard Pro", "Версія 2.0 | Охоронний комплекс",
        "IMEI Changer (Multi-Strategy)", "Система автоматично адаптується під CPU (MTK, Qualcomm, Unisoc, Exynos). Використовує ін'єкції в NVRAM та прямий запис у пам'ять RIL, що гарантує стійкість ID та їх невидимість для операторів.",
        "PDU Firewall (HoneyPot ACK)", "Просунутий захист від Silent SMS (Type-0). При виявленні атаки модуль повертає мережі фейковий код успіху (ACK), повністю блокуючи шкідливий пакет від потрапляння в пристрій.",
        "Binary & OTA Protection", "Повне блокування бінарних SMS та OTA-команд, які намагаються зчитати ключі шифрування SIM-картки (Ki/Kc) або встановити приховані трекер-аплети.",
        "Форензик-логування", "Кожна перехоплена атака записується у сирому HEX-форматі. Це дозволяє експертам аналізувати сигнатуру атакуючого та тип використовуваного обладнання (IMSI Catcher).",
        "Kill Switch & Lockdown", "Якщо за короткий час виявлено більше 3 атак, система може автоматично вимкнути радіомодуль для запобігання тріангуляції та перехоплення даних.",
        "System Integrity", "Працює під UID 1000 із системним підписом. Це забезпечує найвищий рівень пріоритету, дозволяючи програмі обходити стандартні політики безпеки ОС.",
        "Юридична інформація", "Попередження: модифікація IMEI призначена лише для навчання та тестування безпеки. Використовуйте тільки на власному обладнанні. Автор не несе відповідальності за порушення закону.",
        "ЗРОЗУМІЛО"
    ),
    "ru" to listOf(
        "CONSUL IMEI Guard Pro", "Версия 2.0 | Охранный комплекс",
        "IMEI Changer (Multi-Strategy)", "Система автоматически адаптируется под CPU (MTK, Qualcomm, Unisoc, Exynos). Использует инъекции в NVRAM и прямую запись в память RIL, что гарантирует стойкость ID и их невидимость для операторов.",
        "PDU Firewall (HoneyPot ACK)", "Продвинутая защита от Silent SMS (Type-0). При обнаружении атаки модуль возвращает сети фейковый код успеха (ACK), полностью блокируя вредоносный пакет от попадания в устройство.",
        "Binary & OTA Protection", "Полная блокировка бинарных SMS и OTA-команд, пытающихся считать ключи шифрования SIM-карты (Ki/Kc) или установить скрытые трекер-апплеты.",
        "Форензик-логирование", "Каждая перехваченная атака записывается в сыром HEX-формате. Это позволяет экспертам анализировать сигнатуру атакующего и тип используемого оборудования (IMSI Catcher).",
        "Kill Switch & Lockdown", "Если за короткое время обнаружено более 3 атак, система может автоматически отключить радиомодуль для предотвращения триангуляции и перехвата данных.",
        "System Integrity", "Работает под UID 1000 с системной подписью. Это обеспечивает наивысший уровень приоритета, позволяя приложению обходить стандартные политики безопасности ОС.",
        "Юридическая информация", "Внимание: модификация IMEI предназначена только для обучения и тестирования безопасности. Используйте только на собственном оборудовании. Автор не несет ответственности за ваши действия.",
        "ПОНЯТНО"
    ),
    "de" to listOf(
        "CONSUL IMEI Guard Pro", "Version 2.0 | Sicherheitskomplex",
        "IMEI Changer (Multi-Strategie)", "System passt sich automatisch an CPU (MTK, Qualcomm, Unisoc, Exynos) an. Nutzt NVRAM-Injektion und direktes RIL-Schreiben für maximale Persistenz.",
        "PDU Firewall (HoneyPot ACK)", "Schutz vor Silent SMS (Typ-0). Bei Angriffen wird ein gefälschter Erfolgs-Code (ACK) an das Netzwerk gesendet, während das Paket blockiert wird.",
        "Binary & OTA Schutz", "Vollständige Blockierung von binären SMS und OTA-Befehlen, die SIM-Verschlüsselungsschlüssel (Ki/Kc) auslesen oder Tracker-Applets installieren wollen.",
        "Forensische Protokollierung", "Jeder abgefangene Angriff wird im HEX-Format gespeichert. Dies ermöglicht die Analyse der Angreifer-Signatur und des IMSI-Catchers.",
        "Kill Switch & Lockdown", "Bei mehr als 3 Angriffen in kurzer Zeit kann das System das Funkmodul automatisch deaktivieren, um Triangulation zu verhindern.",
        "System Integrität", "Läuft unter UID 1000 mit Systemsignatur. Dies ermöglicht die Umgehung von Standard-OS-Sicherheitsrichtlinien.",
        "Rechtlicher Hinweis", "Warnung: IMEI-Modifikation nur zu Bildungszwecken. Nutzung nur auf eigener Hardware. Der Autor haftet nicht für Rechtsverletzungen.",
        "VERSTANDEN"
    ),
    "lt" to listOf(
        "CONSUL IMEI Guard Pro", "Versija 2.0 | Saugumo kompleksas",
        "IMEI keitimas (Multi-Strategy)", "Sistema automatiškai prisitaiko prie CPU (MTK, Qualcomm, Unisoc, Exynos). Naudoja NVRAM injekcijas ID stabilumui užtikrinti.",
        "PDU ugniasienė (HoneyPot ACK)", "Apsauga nuo Silent SMS (Type-0). Aptikus ataką, tinklui grąžinamas suklastotas sėkmės kodas (ACK), blokuojant paketą.",
        "Binary ir OTA apsauga", "Blokuoja binarines SMS ir OTA komandas, kurios bando nuskaityti SIM šifravimo raktus (Ki/Kc).",
        "Forensinis logavimas", "Kiekviena perimta ataka įrašoma HEX formatu. Tai leidžia ekspertams analizuoti užpuoliko parašą.",
        "Kill Switch ir Lockdown", "Aptikus daugiau nei 3 atakas per trumpą laiką, sistema automatiškai išjungia radijo modulį.",
        "Sistemos vientisumas", "Veikia su UID 1000 ir sistemos parašu. Tai suteikia aukščiausią prioritetą apeiti standartines OS taisykles.",
        "Teisinė informacija", "Įspėjimas: IMEI keitimas skirtas tik mokymams. Naudokite tik savo įrangoje. Autorius neatsako už pasekmes.",
        "SUPRANTAU"
    ),
    "lv" to listOf(
        "CONSUL IMEI Guard Pro", "Versija 2.0 | Drošības komplekss",
        "IMEI maiņa (Multi-Strategy)", "Sistēma automātiski pielāgojas CPU (MTK, Qualcomm, Unisoc, Exynos). Izmanto NVRAM injekcijas ID stabilitātei.",
        "PDU ugunsmūris (HoneyPot ACK)", "Aizsardzība pret Silent SMS (Type-0). Uzbrukuma gadījumā tīklam tiek nosūtīts viltus veiksmes kods (ACK).",
        "Binary un OTA aizsardzība", "Bloķē bināros SMS un OTA komandas, kas mēģina nolasīt SIM šifrēšanas atslēgas (Ki/Kc).",
        "Forensiskā žurnalēšana", "Katrs pārtvertais uzbrukums tiek ierakstīts HEX formātā uzbrucēja paraksta analīzei.",
        "Kill Switch un Lockdown", "Ja īsā laikā tiek konstatēti vairāk nekā 3 uzbrukumi, sistēma automātiski atslēdz radio moduli.",
        "Sistēmas integritāte", "Darbojas ar UID 1000 un sistēmas parakstu. Tas ļauj apiet standarta OS drošības politikas.",
        "Juridiskā informācija", "Brīdinājums: IMEI modifikācija ir paredzēta tikai drošības testēšanai. Autors nenes atbildību par sekām.",
        "SAPRATU"
    ),
    "pl" to listOf(
        "CONSUL IMEI Guard Pro", "Wersja 2.0 | Kompleks bezpieczeństwa",
        "IMEI Changer (Multi-Strategy)", "System automatycznie dostosowuje się do CPU (MTK, Qualcomm, Unisoc, Exynos). Używa wstrzykiwania NVRAM dla trwałości ID.",
        "PDU Firewall (HoneyPot ACK)", "Ochrona przed Silent SMS (Type-0). W przypadku ataku zwraca fałszywy kod sukcesu (ACK) do sieci.",
        "Ochrona Binary & OTA", "Blokowanie binarnych SMS i komend OTA próbujących odczytać klucze szyfrowania SIM (Ki/Kc).",
        "Logowanie forensyczne", "Każdy przechwycony atak jest zapisywany w formacie HEX, co pozwala na analizę sygnatury atakującego.",
        "Kill Switch & Lockdown", "W przypadku wykrycia ponad 3 ataków w krótkim czasie, system automatycznie wyłącza moduł radiowy.",
        "Integralność systemu", "Działa pod UID 1000 z sygnaturą systemową. Pozwala to na omijanie standardowych polityk bezpieczeństwa OS.",
        "Informacje prawne", "Uwaga: Modyfikacja IMEI służy wyłącznie do celów edukacyjnych. Używaj tylko na własnym sprzęcie. Autor nie ponosi odpowiedzialności.",
        "ZROZUMIANO"
    ),
    "es" to listOf(
        "CONSUL IMEI Guard Pro", "Versión 2.0 | Complejo de Seguridad",
        "IMEI Changer (Multi-Strategy)", "El sistema se adapta automáticamente al CPU (MTK, Qualcomm, Unisoc, Exynos). Utiliza inyección NVRAM para persistencia.",
        "PDU Firewall (HoneyPot ACK)", "Protección contra Silent SMS (Type-0). Devuelve un código de éxito falso (ACK) a la red al detectar un ataque.",
        "Protección Binary & OTA", "Bloqueo total de SMS binarios y comandos OTA que intentan leer claves de cifrado SIM (Ki/Kc).",
        "Registro Forense", "Cada ataque interceptado se registra en formato HEX para analizar la firma del atacante.",
        "Kill Switch & Lockdown", "Si se detectan más de 3 ataques en poco tiempo, el sistema desactiva el módulo de radio automáticamente.",
        "Integridad del Sistema", "Funciona bajo UID 1000 con firma de sistema, permitiendo eludir las políticas estándar de seguridad del SO.",
        "Aviso Legal", "Advertencia: La modificación de IMEI es solo para pruebas de seguridad. El autor no se hace responsable del uso indebido.",
        "ENTENDIDO"
    )
)

@Composable
fun AboutDialog(onDismiss: () -> Unit, lang: String = "uk") {
    val s = aboutTrans[lang] ?: aboutTrans["en"]!!
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121316))
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = s[0],
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = s[1],
                    fontSize = 14.sp,
                    color = Color(0xFF7C8CCF),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                AboutSection(s[2], s[3])
                AboutSection(s[4], s[5])
                AboutSection(s[6], s[7])
                AboutSection(s[8], s[9])
                AboutSection(s[10], s[11])
                AboutSection(s[12], s[13])
                
                // Legal Section
                AboutSection(s[14], s[15], isWarning = true)

                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C8CCF))
                ) {
                    Text(s[16], color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AboutSection(title: String, content: String, isWarning: Boolean = false) {
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (isWarning) Color(0xFFF44336) else Color.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = content,
            fontSize = 13.sp,
            color = if (isWarning) Color(0xFFFFCDD2) else Color.Gray,
            lineHeight = 18.sp
        )
    }
}
