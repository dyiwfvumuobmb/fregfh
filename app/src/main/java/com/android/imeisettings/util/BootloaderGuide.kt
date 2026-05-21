package com.android.imeisettings.util

import android.os.Build

object BootloaderGuide {

    data class GuideStep(val title: String, val description: String, val command: String? = null)
    data class DeviceGuide(val brand: String, val model: String, val steps: List<GuideStep>, val warnings: List<String>)

    fun getBootloaderStatus(): String {
        return try {
            val sysPropClass = Class.forName("android.os.SystemProperties")
            val getMethod = sysPropClass.getMethod("get", String::class.java, String::class.java)
            val prop = getMethod.invoke(null, "ro.boot.flash.locked", "") as String
            val secBoot = getMethod.invoke(null, "ro.boot.verifiedbootstate", "") as String
            val oemUnlock = getMethod.invoke(null, "sys.oem_unlock_allowed", "") as String
            when {
                prop == "0" || secBoot == "orange" -> "UNLOCKED"
                prop == "1" || secBoot == "green" -> "LOCKED"
                oemUnlock == "1" -> "OEM UNLOCK ENABLED"
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    fun getGuideForDevice(): DeviceGuide {
        val manufacturer = (Build.MANUFACTURER ?: "").uppercase()
        val model = Build.MODEL ?: "Unknown"
        return when {
            manufacturer == "SAMSUNG" -> getSamsungGuide(model)
            manufacturer in arrayOf("XIAOMI", "POCO", "REDMI") -> getXiaomiGuide(model)
            manufacturer == "REALME" -> getRealmeGuide(model)
            manufacturer == "OPPO" -> getOppoGuide(model)
            manufacturer == "ONEPLUS" -> getOnePlusGuide(model)
            manufacturer == "GOOGLE" -> getGoogleGuide(model)
            else -> getGenericGuide(model)
        }
    }

    private fun getSamsungGuide(model: String): DeviceGuide {
        return DeviceGuide(
            brand = "Samsung",
            model = model,
            steps = listOf(
                GuideStep(
                    "Enable OEM Unlocking",
                    "Settings > Developer Options > OEM Unlocking (toggle ON). If grayed out, connect to WiFi and wait 7 days (Knox timer on new devices)."
                ),
                GuideStep(
                    "Enter Download Mode",
                    "Power off. Hold Volume Down + Volume Up, connect USB cable."
                ),
                GuideStep(
                    "Unlock via Odin/Heimdall",
                    "Press Volume Up to unlock. WARNING: This triggers Knox and wipes all data.",
                    command = "heimdall flash --RECOVERY recovery.img"
                ),
                GuideStep(
                    "Flash Custom Recovery",
                    "Use Odin (Windows) or Heimdall (Linux/Mac) to flash TWRP/OrangeFox.",
                    command = "heimdall flash --RECOVERY twrp.img"
                ),
                GuideStep(
                    "Install System App",
                    "After unlock, flash the firmware with CONSUL IMEI as system app signed with platform key."
                )
            ),
            warnings = listOf(
                "Knox will be permanently tripped (e-fuse blown).",
                "Samsung Pay, Secure Folder, and some banking apps may stop working.",
                "Warranty void on most Samsung models.",
                "New Samsung models (2024+) may have 7-day OEM unlock timer.",
                "Galaxy A05/A06: OEM unlock may be permanently disabled on carrier-locked variants."
            )
        )
    }

    private fun getXiaomiGuide(model: String): DeviceGuide {
        return DeviceGuide(
            brand = "Xiaomi/POCO/Redmi",
            model = model,
            steps = listOf(
                GuideStep(
                    "Create Mi Account",
                    "Register at account.xiaomi.com. Bind your phone number."
                ),
                GuideStep(
                    "Enable OEM Unlocking",
                    "Settings > Developer Options > OEM Unlocking + Mi Unlock status."
                ),
                GuideStep(
                    "Bind Account to Device",
                    "Settings > Developer Options > Mi Unlock status > Add account and device. Wait 168-720 hours (7-30 days)."
                ),
                GuideStep(
                    "Download Mi Unlock Tool",
                    "Download from en.miui.com/unlock/download_en.html. Only for Windows.",
                    command = null
                ),
                GuideStep(
                    "Enter Fastboot Mode",
                    "Power off. Hold Volume Down + Power button until Fastboot logo appears."
                ),
                GuideStep(
                    "Unlock with Mi Unlock Tool",
                    "Connect phone via USB. Run Mi Unlock Tool, login, click Unlock.",
                    command = "fastboot oem unlock"
                ),
                GuideStep(
                    "Flash System Partition",
                    "After unlock, use fastboot to flash custom firmware or system partition with CONSUL IMEI."
                )
            ),
            warnings = listOf(
                "Waiting period: 168 hours (7 days) for most models, up to 720 hours (30 days) for newer ones.",
                "HyperOS 2.0 (2024+): Some models require 'Community Level 5' in Xiaomi Community app.",
                "Xiaomi 14/15 Pro: May require special unlock code from Xiaomi support.",
                "POCO F6/X6: Standard Mi Unlock Tool works, 7-day wait.",
                "Redmi Note 13 series: 168h wait, standard process.",
                "All data will be wiped during unlock."
            )
        )
    }

    private fun getRealmeGuide(model: String): DeviceGuide {
        return DeviceGuide(
            brand = "Realme",
            model = model,
            steps = listOf(
                GuideStep(
                    "Apply for Deep Testing",
                    "Go to realme.com/in/unlock or Settings > About Phone > tap Build Number 7 times > Developer Options."
                ),
                GuideStep(
                    "Submit Unlock Application",
                    "In Developer Options, find 'Deep Testing' or apply via Realme Community. Requires Realme account."
                ),
                GuideStep(
                    "Install Deep Testing APK",
                    "After approval, download the Deep Testing APK from Realme. Install it on the device."
                ),
                GuideStep(
                    "Start Deep Testing",
                    "Open Deep Testing app. Click 'Start Deep Testing'. Follow on-screen instructions."
                ),
                GuideStep(
                    "Enter Fastboot Mode",
                    "Power off. Hold Volume Down + Power button.",
                    command = "fastboot flashing unlock"
                ),
                GuideStep(
                    "Unlock Bootloader",
                    "Connect to PC. Run fastboot flashing unlock command.",
                    command = "fastboot flashing unlock"
                ),
                GuideStep(
                    "Flash System Image",
                    "After unlock, flash the firmware with CONSUL IMEI as system app."
                )
            ),
            warnings = listOf(
                "Realme GT 6/GT 5 Pro: Deep Testing application needed (approval 1-3 days).",
                "Realme 12/13 Pro+: Some variants have unlock restrictions in certain regions.",
                "Realme Narzo 70 Pro: Standard Deep Testing process.",
                "Realme UI 5.0+ (Android 14+): Deep Testing may require community app verification.",
                "Realme C-series: Usually easier to unlock, standard fastboot.",
                "All data will be erased on unlock.",
                "ColorOS/Realme UI security features will be disabled."
            )
        )
    }

    private fun getOppoGuide(model: String): DeviceGuide {
        return DeviceGuide(
            brand = "OPPO",
            model = model,
            steps = listOf(
                GuideStep("Apply via OPPO Community", "OPPO has restricted bootloader unlock since 2023. Contact OPPO support or use community tools."),
                GuideStep("Enter Fastboot", "Power off. Hold Volume Down + Power.", command = "fastboot flashing unlock"),
                GuideStep("Flash System", "After unlock, flash firmware with CONSUL IMEI.")
            ),
            warnings = listOf(
                "OPPO has severely restricted bootloader unlocking since ColorOS 13+.",
                "Most new OPPO devices cannot be officially unlocked."
            )
        )
    }

    private fun getOnePlusGuide(model: String): DeviceGuide {
        return DeviceGuide(
            brand = "OnePlus",
            model = model,
            steps = listOf(
                GuideStep("Enable OEM Unlocking", "Settings > Developer Options > OEM Unlocking."),
                GuideStep("Enter Fastboot", "Power off. Hold Volume Up + Volume Down + Power.", command = "fastboot oem unlock"),
                GuideStep("Unlock", "On device, use volume keys to select UNLOCK, press Power.", command = "fastboot oem unlock"),
                GuideStep("Flash System", "After unlock, flash firmware with CONSUL IMEI.")
            ),
            warnings = listOf("OnePlus 12+: Standard process, no waiting period. Data will be wiped.")
        )
    }

    private fun getGoogleGuide(model: String): DeviceGuide {
        return DeviceGuide(
            brand = "Google",
            model = model,
            steps = listOf(
                GuideStep("Enable OEM Unlocking", "Settings > Developer Options > OEM Unlocking."),
                GuideStep("Enter Fastboot", "Power off. Hold Volume Down + Power."),
                GuideStep("Unlock", "fastboot flashing unlock", command = "fastboot flashing unlock"),
                GuideStep("Flash System", "After unlock, flash factory image with CONSUL IMEI as system app.")
            ),
            warnings = listOf("Pixel devices: Straightforward unlock. Carrier-locked variants may not support OEM unlock.")
        )
    }

    private fun getGenericGuide(model: String): DeviceGuide {
        return DeviceGuide(
            brand = Build.MANUFACTURER,
            model = model,
            steps = listOf(
                GuideStep("Enable Developer Options", "Settings > About Phone > tap Build Number 7 times."),
                GuideStep("Enable OEM Unlocking", "Settings > Developer Options > OEM Unlocking (if available)."),
                GuideStep("Enter Fastboot", "Power off. Hold Volume Down + Power (most devices)."),
                GuideStep("Unlock Bootloader", "Use fastboot flashing unlock or fastboot oem unlock.", command = "fastboot flashing unlock")
            ),
            warnings = listOf("Generic instructions. Consult device-specific forums for exact steps.")
        )
    }
}
