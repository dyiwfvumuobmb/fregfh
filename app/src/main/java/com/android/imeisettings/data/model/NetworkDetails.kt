package com.android.imeisettings.data.model

data class NetworkDetails(
    val operator: String = "N/A",
    val mccMnc: String = "--- / ---",
    val asnInfo: String = "N/A",
    val asnNumber: String = "",
    val cellId: String = "---",
    val lacTac: String = "---",
    val pciPsc: String = "---",
    val arfcn: String = "---",
    val band: String = "Auto",
    val neighbors: Int = 0,
    val neighborSignals: String = "", // e.g. "-78, -85, -92 dBm"
    val roaming: String = "OFF",
    val encryption: String = "N/A",
    val dataState: String = "DISCONNECTED",
    val voiceState: String = "NO SIM",
    val networkType: String = "N/A",
    val signalDbm: Int = -120
)
