package com.android.imeisettings.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "imei_backup")
data class ImeiBackup(
    @PrimaryKey val slotIndex: Int,
    val originalImei: String,
    val backupTimestamp: Long,
    val deviceModel: String = "",
    val isRestored: Boolean = false
)
