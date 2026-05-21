package com.android.imeisettings.data.local

import androidx.room.*

@Entity(tableName = "cell_tower_map")
data class CellTowerMapData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cellId: Long,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val latitude: Double,
    val longitude: Double,
    val signalStrength: Int,
    val networkType: Int,
    val isSuspicious: Boolean,
    val suspiciousReason: String?,
    val isVerifiedOpenCelliD: Boolean = false,
    val anomalyScore: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface CellTowerMapDao {
    @Query("SELECT * FROM cell_tower_map ORDER BY timestamp DESC LIMIT 500")
    fun getRecentTowers(): List<CellTowerMapData>

    @Query("SELECT * FROM cell_tower_map WHERE isSuspicious = 1 ORDER BY timestamp DESC")
    fun getSuspiciousTowers(): List<CellTowerMapData>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tower: CellTowerMapData)

    @Query("DELETE FROM cell_tower_map WHERE timestamp < :before")
    suspend fun deleteOldEntries(before: Long)

    @Query("SELECT COUNT(*) FROM cell_tower_map WHERE isSuspicious = 1")
    fun getSuspiciousCount(): Int

    @Query("SELECT * FROM cell_tower_map WHERE cellId = :cellId AND lac = :lac LIMIT 1")
    fun findByCell(cellId: Long, lac: Int): CellTowerMapData?
}
