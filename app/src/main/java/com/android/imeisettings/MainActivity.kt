package com.android.imeisettings

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.android.imeisettings.data.local.SettingsDataStore
import com.android.imeisettings.service.NetworkSecurityService
import com.android.imeisettings.service.PduInterceptorService
import com.android.imeisettings.ui.Screen
import com.android.imeisettings.ui.home.HomeScreen
import com.android.imeisettings.ui.security.SafeZoneScreen
import com.android.imeisettings.ui.security.SecurityScreen
import com.android.imeisettings.ui.signal.SignalScreen
import com.android.imeisettings.ui.bootloader.BootloaderScreen
import com.android.imeisettings.ui.settings.SettingsScreen
import com.android.imeisettings.ui.history.ImeiHistoryScreen
import com.android.imeisettings.ui.backup.ImeiBackupScreen
import com.android.imeisettings.ui.lock.AppLockScreen
import com.android.imeisettings.ui.theme.CONSULIMEITheme
import com.android.imeisettings.util.HiddenApiBypass

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkAdvancedPermissionsAndStart()
    }

    private val navTrans = mapOf(
        "en" to mapOf("nav_home" to "Home", "nav_signal" to "Signal", "nav_security" to "Security"),
        "uk" to mapOf("nav_home" to "Головна", "nav_signal" to "Сигнал", "nav_security" to "Безпека"),
        "ru" to mapOf("nav_home" to "Главная", "nav_signal" to "Сигнал", "nav_security" to "Безопасность"),
        "de" to mapOf("nav_home" to "Start", "nav_signal" to "Signal", "nav_security" to "Sicherheit"),
        "pl" to mapOf("nav_home" to "Główna", "nav_signal" to "Sygnał", "nav_security" to "Ochrona"),
        "lt" to mapOf("nav_home" to "Pagrindinis", "nav_signal" to "Signalas", "nav_security" to "Saugumas"),
        "lv" to mapOf("nav_home" to "Sākums", "nav_signal" to "Signāls", "nav_security" to "Drošība"),
        "es" to mapOf("nav_home" to "Inicio", "nav_signal" to "Señal", "nav_security" to "Seguridad")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Bypass Hidden API restrictions for hardware access
        try {
            HiddenApiBypass.bypassRestrictions()
        } catch (e: Exception) {
            android.util.Log.e("CONSUL_MAIN", "HiddenApiBypass failed: ${e.message}")
        }
        
        // Ensure the activity can show over the lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        checkAndRequestPermissions()
        
        setContent {
            val context = this
            val settingsDataStore = remember { SettingsDataStore.getInstance(context) }
            val currentLang by settingsDataStore.selectedLanguage.collectAsStateWithLifecycle(initialValue = "uk")
            
            val appLockEnabled by settingsDataStore.appLockEnabled.collectAsStateWithLifecycle(initialValue = false)
            val stealthMode by settingsDataStore.stealthMode.collectAsStateWithLifecycle(initialValue = false)
            var isUnlocked by remember { mutableStateOf(false) }

            // Apply stealth mode: exclude from recent tasks
            LaunchedEffect(stealthMode) {
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.appTasks.forEach { it.setExcludeFromRecents(stealthMode) }
                } catch (e: Exception) {
                    android.util.Log.e("CONSUL_MAIN", "Stealth mode error: ${e.message}")
                }
            }

            CONSULIMEITheme {
                if (appLockEnabled && !isUnlocked) {
                    AppLockScreen(onUnlocked = { isUnlocked = true })
                    return@CONSULIMEITheme
                }

                val navController = rememberNavController()
                val screens = listOf(Screen.Home, Screen.Signal, Screen.Security)
                val langStrings = navTrans[currentLang] ?: navTrans["en"]!!

                var pendingCellId by remember { mutableStateOf<String?>(null) }
                var pendingZoneId by remember { mutableIntStateOf(-1) }
                
                LaunchedEffect(intent) {
                    if (intent?.getBooleanExtra("GO_TO_SECURITY", false) == true) {
                        pendingCellId = intent?.getStringExtra("PENDING_CELL_ID")
                        pendingZoneId = intent?.getIntExtra("PENDING_ZONE_ID", -1) ?: -1
                        
                        navController.navigate(Screen.Security.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentDestination = navBackStackEntry?.destination
                            screens.forEach { screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = null) },
                                    label = { Text(langStrings[screen.titleKey] ?: screen.titleKey) },
                                    selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen()
                        }
                        composable(Screen.Signal.route) { SignalScreen() }
                        composable(Screen.Security.route) { 
                            SecurityScreen(
                                onNavigateToSafeZones = { navController.navigate("safe_zones") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                pendingCellId = pendingCellId,
                                pendingZoneId = pendingZoneId,
                                onClearPending = {
                                    pendingCellId = null
                                    pendingZoneId = -1
                                }
                            ) 
                        }
                        composable("safe_zones") {
                            SafeZoneScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("bootloader") {
                            BootloaderScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("imei_history") {
                            ImeiHistoryScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("imei_backup") {
                            ImeiBackupScreen(onNavigateBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissionsToRequest.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            checkAdvancedPermissionsAndStart()
        }
    }

    private fun checkAdvancedPermissionsAndStart() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                // Don't return — still start services, overlay is not critical for startup
            }
        } catch (e: Exception) {
            android.util.Log.e("CONSUL_MAIN", "Overlay permission check failed: ${e.message}")
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("CONSUL_MAIN", "Battery optimization check failed: ${e.message}")
        }

        startServices()
    }

    private fun startServices() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, NetworkSecurityService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("CONSUL_MAIN", "Failed to start NetworkSecurityService: ${e.message}")
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, PduInterceptorService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("CONSUL_MAIN", "Failed to start PduInterceptorService: ${e.message}")
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (checkAllPermissionsGranted()) {
            startServices()
        }
    }

    private fun checkAllPermissionsGranted(): Boolean {
        return try {
            val basePermissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
            basePermissions
        } catch (e: Exception) {
            android.util.Log.e("CONSUL_MAIN", "Permission check error: ${e.message}")
            true // Start services anyway
        }
    }
}
