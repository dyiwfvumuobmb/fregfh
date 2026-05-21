package com.android.imeisettings.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SettingsDataStore? = null

        fun getInstance(context: Context): SettingsDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        @Deprecated("Use getInstance(context) for singleton access", ReplaceWith("getInstance(context)"))
        operator fun invoke(context: Context): SettingsDataStore = getInstance(context)

        private fun hashPin(pin: String): String {
            if (pin.isBlank()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
        val PREVENT_REPEATS = booleanPreferencesKey("prevent_repeats")
        val TRUSTED_ASN_SET = stringSetPreferencesKey("trusted_asns")
        val SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        val AUTO_CLEAN_LOGS = booleanPreferencesKey("auto_clean_logs")
        val CLEAN_LOGS_ON_IMEI_CHANGE = booleanPreferencesKey("clean_logs_on_imei_change")
        val AUTO_REBOOT_AFTER_IMEI = booleanPreferencesKey("auto_reboot_after_imei")
        val KILL_SWITCH_ENABLED = booleanPreferencesKey("kill_switch_enabled")
        val RADIO_LOG_MONITOR_ENABLED = booleanPreferencesKey("radio_log_monitor_enabled")
        val STEALTH_MODE = booleanPreferencesKey("stealth_mode")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN = stringPreferencesKey("app_lock_pin")
        val USE_BIOMETRIC = booleanPreferencesKey("use_biometric")
        val AUTO_ROTATE_ENABLED = booleanPreferencesKey("auto_rotate_enabled")
        val AUTO_ROTATE_INTERVAL_HOURS = intPreferencesKey("auto_rotate_interval_hours")
        val AUTO_ROTATE_ON_NETWORK_CHANGE = booleanPreferencesKey("auto_rotate_on_network_change")
        val LEARNING_MODE_ACTIVE = booleanPreferencesKey("learning_mode_active")
        val LEARNING_MODE_END_TIME = longPreferencesKey("learning_mode_end_time")
        val WEAR_BLE_ALERT_ENABLED = booleanPreferencesKey("wear_ble_alert_enabled")
    }  // end companion object

    val preventRepeats: Flow<Boolean> = context.dataStore.data.map { it[PREVENT_REPEATS] ?: false }
    val userTrustedAsns: Flow<Set<String>> = context.dataStore.data.map { it[TRUSTED_ASN_SET] ?: emptySet() }
    val selectedLanguage: Flow<String> = context.dataStore.data.map { it[SELECTED_LANGUAGE] ?: "uk" }
    val autoCleanLogs: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CLEAN_LOGS] ?: false }
    val cleanLogsOnImeiChange: Flow<Boolean> = context.dataStore.data.map { it[CLEAN_LOGS_ON_IMEI_CHANGE] ?: true }
    val autoRebootAfterImei: Flow<Boolean> = context.dataStore.data.map { it[AUTO_REBOOT_AFTER_IMEI] ?: true }
    val killSwitchEnabled: Flow<Boolean> = context.dataStore.data.map { it[KILL_SWITCH_ENABLED] ?: true }
    val radioLogMonitorEnabled: Flow<Boolean> = context.dataStore.data.map { it[RADIO_LOG_MONITOR_ENABLED] ?: true }
    val stealthMode: Flow<Boolean> = context.dataStore.data.map { it[STEALTH_MODE] ?: false }
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[APP_LOCK_ENABLED] ?: false }
    val appLockPin: Flow<String> = context.dataStore.data.map { it[APP_LOCK_PIN] ?: "" }
    val useBiometric: Flow<Boolean> = context.dataStore.data.map { it[USE_BIOMETRIC] ?: true }
    val autoRotateEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_ROTATE_ENABLED] ?: false }
    val autoRotateIntervalHours: Flow<Int> = context.dataStore.data.map { it[AUTO_ROTATE_INTERVAL_HOURS] ?: 24 }
    val autoRotateOnNetworkChange: Flow<Boolean> = context.dataStore.data.map { it[AUTO_ROTATE_ON_NETWORK_CHANGE] ?: false }
    val learningModeActive: Flow<Boolean> = context.dataStore.data.map { it[LEARNING_MODE_ACTIVE] ?: false }
    val learningModeEndTime: Flow<Long> = context.dataStore.data.map { it[LEARNING_MODE_END_TIME] ?: 0L }
    val wearBleAlertEnabled: Flow<Boolean> = context.dataStore.data.map { it[WEAR_BLE_ALERT_ENABLED] ?: false }

    suspend fun setAutoCleanLogs(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setAutoCleanLogs: $enabled")
        context.dataStore.edit { it[AUTO_CLEAN_LOGS] = enabled }
    }

    suspend fun setCleanLogsOnImeiChange(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setCleanLogsOnImeiChange: $enabled")
        context.dataStore.edit { it[CLEAN_LOGS_ON_IMEI_CHANGE] = enabled }
    }

    suspend fun setPreventRepeats(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setPreventRepeats: $enabled")
        context.dataStore.edit { it[PREVENT_REPEATS] = enabled }
    }

    suspend fun setLanguage(lang: String) {
        Log.d("CONSUL_SETTINGS", "setLanguage: $lang")
        context.dataStore.edit { it[SELECTED_LANGUAGE] = lang }
    }

    suspend fun setAutoRebootAfterImei(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setAutoRebootAfterImei: $enabled")
        context.dataStore.edit { it[AUTO_REBOOT_AFTER_IMEI] = enabled }
    }

    suspend fun setKillSwitchEnabled(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setKillSwitchEnabled: $enabled")
        context.dataStore.edit { it[KILL_SWITCH_ENABLED] = enabled }
    }

    suspend fun setRadioLogMonitorEnabled(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setRadioLogMonitorEnabled: $enabled")
        context.dataStore.edit { it[RADIO_LOG_MONITOR_ENABLED] = enabled }
    }

    suspend fun setStealthMode(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setStealthMode: $enabled")
        context.dataStore.edit { it[STEALTH_MODE] = enabled }
    }

    suspend fun trustAsn(asn: String) {
        Log.d("CONSUL_SETTINGS", "trustAsn: $asn")
        context.dataStore.edit { prefs ->
            val current = prefs[TRUSTED_ASN_SET] ?: emptySet()
            prefs[TRUSTED_ASN_SET] = current + asn
        }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setAppLockEnabled: $enabled")
        context.dataStore.edit { it[APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setAppLockPin(pin: String) {
        Log.d("CONSUL_SETTINGS", "setAppLockPin: [HIDDEN]")
        context.dataStore.edit { it[APP_LOCK_PIN] = hashPin(pin) }
    }

    fun verifyPin(inputPin: String, storedHash: String): Boolean {
        if (storedHash.isBlank()) return true
        return hashPin(inputPin) == storedHash
    }

    suspend fun setUseBiometric(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setUseBiometric: $enabled")
        context.dataStore.edit { it[USE_BIOMETRIC] = enabled }
    }

    suspend fun setAutoRotateEnabled(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setAutoRotateEnabled: $enabled")
        context.dataStore.edit { it[AUTO_ROTATE_ENABLED] = enabled }
    }

    suspend fun setAutoRotateIntervalHours(hours: Int) {
        Log.d("CONSUL_SETTINGS", "setAutoRotateIntervalHours: $hours")
        context.dataStore.edit { it[AUTO_ROTATE_INTERVAL_HOURS] = hours }
    }

    suspend fun setAutoRotateOnNetworkChange(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setAutoRotateOnNetworkChange: $enabled")
        context.dataStore.edit { it[AUTO_ROTATE_ON_NETWORK_CHANGE] = enabled }
    }

    suspend fun setLearningMode(active: Boolean, durationMs: Long = 60 * 60 * 1000L) {
        Log.d("CONSUL_SETTINGS", "setLearningMode: $active, durationMs: $durationMs")
        context.dataStore.edit {
            it[LEARNING_MODE_ACTIVE] = active
            it[LEARNING_MODE_END_TIME] = if (active) System.currentTimeMillis() + durationMs else 0L
        }
    }

    suspend fun setWearBleAlertEnabled(enabled: Boolean) {
        Log.d("CONSUL_SETTINGS", "setWearBleAlertEnabled: $enabled")
        context.dataStore.edit { it[WEAR_BLE_ALERT_ENABLED] = enabled }
    }

    suspend fun untrustAsn(asn: String) {
        Log.d("CONSUL_SETTINGS", "untrustAsn: $asn")
        context.dataStore.edit { prefs ->
            val current = prefs[TRUSTED_ASN_SET] ?: emptySet()
            prefs[TRUSTED_ASN_SET] = current - asn
        }
    }
}
