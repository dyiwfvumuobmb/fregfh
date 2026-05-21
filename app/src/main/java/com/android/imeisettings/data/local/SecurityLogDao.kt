package com.android.imeisettings.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurityLogDao {
    @Query("SELECT * FROM security_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<SecurityLog>>

    @Insert
    suspend fun insertLog(log: SecurityLog)

    @Query("DELETE FROM security_logs")
    suspend fun deleteAllLogs()

    @Query("DELETE FROM security_logs WHERE timestamp < :cutoff")
    suspend fun deleteLogsBefore(cutoff: Long)
}
