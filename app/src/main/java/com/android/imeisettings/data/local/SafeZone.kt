package com.android.imeisettings.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "safe_zones")
data class SafeZone(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val mccMnc: String, // Код страны и оператора (напр. "25501")
    val lacTac: String, // Код зоны (LAC или TAC)
    val trustedCellIds: String // Список ID вышек в этой зоне через запятую
)

@Dao
interface SafeZoneDao {
    @Query("SELECT * FROM safe_zones")
    suspend fun getAllSafeZones(): List<SafeZone>

    @Query("SELECT * FROM safe_zones")
    fun getAllSafeZonesFlow(): Flow<List<SafeZone>>

    @Query("SELECT * FROM safe_zones WHERE id = :id")
    suspend fun getSafeZoneById(id: Int): SafeZone?

    @Query("SELECT * FROM safe_zones WHERE mccMnc = :mccMnc AND lacTac = :lacTac LIMIT 1")
    suspend fun getSafeZoneByLocation(mccMnc: String, lacTac: String): SafeZone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSafeZone(safeZone: SafeZone)

    @Query("DELETE FROM safe_zones WHERE id = :id")
    suspend fun deleteSafeZone(id: Int)
}
