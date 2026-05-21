package com.android.imeisettings.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "cell_fingerprints")
data class CellFingerprint(
    @PrimaryKey val cellId: String,
    val lacTac: String,
    val arfcn: String,
    val dbm: Int,
    val timestamp: Long
)

@Dao
interface CellFingerprintDao {
    @Query("SELECT * FROM cell_fingerprints ORDER BY timestamp DESC LIMIT 10")
    suspend fun getRecentFingerprints(): List<CellFingerprint>

    @Query("SELECT * FROM cell_fingerprints WHERE cellId = :cellId LIMIT 1")
    suspend fun getFingerprintById(cellId: String): CellFingerprint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFingerprint(fingerprint: CellFingerprint)

    @Query("DELETE FROM cell_fingerprints WHERE cellId NOT IN (SELECT cellId FROM cell_fingerprints ORDER BY timestamp DESC LIMIT 10)")
    suspend fun deleteOldFingerprints()
    
    @Query("DELETE FROM cell_fingerprints")
    suspend fun deleteAll()
}
