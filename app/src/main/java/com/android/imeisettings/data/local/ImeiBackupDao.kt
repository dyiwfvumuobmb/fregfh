package com.android.imeisettings.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImeiBackupDao {
    @Query("SELECT * FROM imei_backup ORDER BY slotIndex ASC")
    fun getAllBackups(): Flow<List<ImeiBackup>>

    @Query("SELECT * FROM imei_backup WHERE slotIndex = :slot")
    suspend fun getBackup(slot: Int): ImeiBackup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(backup: ImeiBackup)

    @Query("DELETE FROM imei_backup WHERE slotIndex = :slot")
    suspend fun deleteBackup(slot: Int)

    @Query("DELETE FROM imei_backup")
    suspend fun deleteAll()
}
