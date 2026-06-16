package com.android.imeisettings.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImeiHistoryDao {
    @Query("SELECT * FROM imei_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<ImeiHistory>>

    @Query("SELECT * FROM imei_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastEntry(): ImeiHistory?

    @Insert
    suspend fun insert(entry: ImeiHistory)

    @Query("DELETE FROM imei_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM imei_history")
    suspend fun getCount(): Int
}
