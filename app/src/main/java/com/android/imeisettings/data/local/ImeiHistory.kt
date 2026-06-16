package com.android.imeisettings.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "imei_history")
data class ImeiHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val sim1OldImei: String,
    val sim1NewImei: String,
    val sim2OldImei: String,
    val sim2NewImei: String,
    val method: String,
    val success: Boolean,
    val deviceModel: String = ""
)
