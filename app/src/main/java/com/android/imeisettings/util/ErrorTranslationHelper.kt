package com.android.imeisettings.util

object ErrorTranslationHelper {
    fun getFriendlyError(lang: String, technicalError: String?): String {
        val err = technicalError ?: ""
        val errorKey = when {
            err.contains("NvRAMUtils") -> "ERR_NVRAM"
            err.contains("Luhn") -> "ERR_LUHN"
            err.contains("Permission denied") || err.contains("Access Denied") -> "ERR_PERMISSION"
            err.contains("ServiceManager") || err.contains("RIL") -> "ERR_RIL"
            err.contains("timeout") -> "ERR_TIMEOUT"
            err.contains("Injection methods returned failure") -> "ERR_INJECTION_FAILED"
            else -> "ERR_GENERIC"
        }

        return translations[lang]?.get(errorKey) ?: translations["en"]?.get(errorKey) ?: err
    }

    private val translations = mapOf(
        "en" to mapOf(
            "ERR_NVRAM" to "System NVRAM interface is blocked or not found.",
            "ERR_LUHN" to "Invalid IMEI format (checksum failed).",
            "ERR_PERMISSION" to "Root access or System privileges denied.",
            "ERR_RIL" to "Modem communication bus (RIL) is unavailable.",
            "ERR_TIMEOUT" to "Operation timed out. Try again.",
            "ERR_INJECTION_FAILED" to "All security injection methods failed.",
            "ERR_GENERIC" to "An unknown system error occurred."
        ),
        "uk" to mapOf(
            "ERR_NVRAM" to "Інтерфейс NVRAM заблоковано або не знайдено.",
            "ERR_LUHN" to "Невірний формат IMEI (контрольна сума).",
            "ERR_PERMISSION" to "Відмовлено у доступі Root або системних привілеях.",
            "ERR_RIL" to "Шина зв'язку з модемом (RIL) недоступна.",
            "ERR_TIMEOUT" to "Час очікування операції вичерпано. Спробуйте ще раз.",
            "ERR_INJECTION_FAILED" to "Всі методи ін'єкції безпеки не спрацювали.",
            "ERR_GENERIC" to "Сталася невідома системна помилка."
        ),
        "ru" to mapOf(
            "ERR_NVRAM" to "Интерфейс NVRAM заблокирован или не найден.",
            "ERR_LUHN" to "Неверный формат IMEI (контрольная сумма).",
            "ERR_PERMISSION" to "Отказано в доступе Root или системных привилегиях.",
            "ERR_RIL" to "Шина связи с модемом (RIL) недоступна.",
            "ERR_TIMEOUT" to "Время ожидания операции истекло. Попробуйте снова.",
            "ERR_INJECTION_FAILED" to "Все методы инъекции безопасности не сработали.",
            "ERR_GENERIC" to "Произошла неизвестная системная ошибка."
        ),
        "de" to mapOf(
            "ERR_NVRAM" to "System-NVRAM-Schnittstelle blockiert oder nicht gefunden.",
            "ERR_LUHN" to "Ungültiges IMEI-Format (Prüfsummenfehler).",
            "ERR_PERMISSION" to "Root-Zugriff oder Systemprivilegien verweigert.",
            "ERR_RIL" to "Modem-Kommunikationsbus (RIL) nicht verfügbar.",
            "ERR_TIMEOUT" to "Zeitüberschreitung der Operation. Versuchen Sie es erneut.",
            "ERR_INJECTION_FAILED" to "Alle Sicherheits-Injektionsmethoden sind fehlgeschlagen.",
            "ERR_GENERIC" to "Ein unbekannter Systemfehler ist aufgetreten."
        ),
        "pl" to mapOf(
            "ERR_NVRAM" to "Interfejs systemowy NVRAM jest zablokowany lub nie znaleziony.",
            "ERR_LUHN" to "Nieprawidłowy format IMEI (błąd sumy kontrolnej).",
            "ERR_PERMISSION" to "Odmowa dostępu Root lub uprawnień systemowych.",
            "ERR_RIL" to "Magistrala komunikacyjna modemu (RIL) jest niedostępna.",
            "ERR_TIMEOUT" to "Limit czasu operacji wygasł. Spróbuj ponownie.",
            "ERR_INJECTION_FAILED" to "Wszystkie metody wstrzykiwania zabezpieczeń zawiodły.",
            "ERR_GENERIC" to "Wystąpił nieznany błąd systemu."
        ),
        "lt" to mapOf(
            "ERR_NVRAM" to "Sistemos NVRAM sąsaja užblokuota arba nerasta.",
            "ERR_LUHN" to "Neteisingas IMEI formatas (kontrolinė suma).",
            "ERR_PERMISSION" to "Root prieiga arba sistemos privilegijos atmestos.",
            "ERR_RIL" to "Modemo ryšio magistralė (RIL) nepasiekiama.",
            "ERR_TIMEOUT" to "Operacijos laikas baigėsi. Bandykite dar kartą.",
            "ERR_INJECTION_FAILED" to "Visi saugumo įpurškimo metodai nepavyko.",
            "ERR_GENERIC" to "Įvyko nežinoma sistemos klaida."
        ),
        "lv" to mapOf(
            "ERR_NVRAM" to "Sistēmas NVRAM saskarne ir bloķēta vai nav atrasta.",
            "ERR_LUHN" to "Nederīgs IMEI formāts (kontrolsummas kļūda).",
            "ERR_PERMISSION" to "Root piekļuve vai sistēmas privilēģijas noraidītas.",
            "ERR_RIL" to "Modema sakaru kopne (RIL) nav pieejama.",
            "ERR_TIMEOUT" to "Operācijas noildze. Mēģiniet vēlreiz.",
            "ERR_INJECTION_FAILED" to "Visas drošības injekcijas metodes neizdevās.",
            "ERR_GENERIC" to "Radās nezināma sistēmas kļūda."
        ),
        "es" to mapOf(
            "ERR_NVRAM" to "La interfaz NVRAM del sistema está bloqueada o no se encuentra.",
            "ERR_LUHN" to "Formato IMEI no válido (falló la suma de verificación).",
            "ERR_PERMISSION" to "Acceso Root o privilegios de sistema denegados.",
            "ERR_RIL" to "El bus de comunicación del módem (RIL) no está disponible.",
            "ERR_TIMEOUT" to "Se agotó el tiempo de espera de la operación. Inténtalo de nuevo.",
            "ERR_INJECTION_FAILED" to "Todos los métodos de inyección de seguridad fallaron.",
            "ERR_GENERIC" to "Ocurrió un error de sistema desconocido."
        )
    )
}
