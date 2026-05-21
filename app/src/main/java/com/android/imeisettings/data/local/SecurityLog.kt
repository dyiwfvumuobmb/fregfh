package com.android.imeisettings.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "security_logs")
data class SecurityLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String, // INFO, WARNING, ALERT
    val message: String,
    val simSlot: Int? = null,
    val lac: Int? = null,
    val cid: Int? = null,
    val pdu: String? = null
)
