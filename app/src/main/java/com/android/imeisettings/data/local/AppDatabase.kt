package com.android.imeisettings.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SecurityLog::class, SafeZone::class, CellFingerprint::class,
        ImeiHistory::class, ImeiBackup::class, CellTowerMapData::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun securityLogDao(): SecurityLogDao
    abstract fun safeZoneDao(): SafeZoneDao
    abstract fun cellFingerprintDao(): CellFingerprintDao
    abstract fun imeiHistoryDao(): ImeiHistoryDao
    abstract fun imeiBackupDao(): ImeiBackupDao
    abstract fun cellTowerMapDao(): CellTowerMapDao

    companion object {
        private const val TAG = "AppDatabase"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d(TAG, "Migrating database from version 7 to 8")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d(TAG, "Migrating database from version 9 to 10")
                db.execSQL("""CREATE TABLE IF NOT EXISTS cell_tower_map (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    cellId INTEGER NOT NULL,
                    lac INTEGER NOT NULL,
                    mcc INTEGER NOT NULL,
                    mnc INTEGER NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    signalStrength INTEGER NOT NULL,
                    networkType INTEGER NOT NULL,
                    isSuspicious INTEGER NOT NULL,
                    suspiciousReason TEXT,
                    isVerifiedOpenCelliD INTEGER NOT NULL DEFAULT 0,
                    anomalyScore REAL NOT NULL DEFAULT 0.0,
                    timestamp INTEGER NOT NULL
                )""")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d(TAG, "Migrating database from version 8 to 9")
                db.execSQL("""CREATE TABLE IF NOT EXISTS imei_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    sim1OldImei TEXT NOT NULL,
                    sim1NewImei TEXT NOT NULL,
                    sim2OldImei TEXT NOT NULL,
                    sim2NewImei TEXT NOT NULL,
                    method TEXT NOT NULL,
                    success INTEGER NOT NULL,
                    deviceModel TEXT NOT NULL DEFAULT ''
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS imei_backup (
                    slotIndex INTEGER PRIMARY KEY NOT NULL,
                    originalImei TEXT NOT NULL,
                    backupTimestamp INTEGER NOT NULL,
                    deviceModel TEXT NOT NULL DEFAULT '',
                    isRestored INTEGER NOT NULL DEFAULT 0
                )""")
            }
        }

        private val SAFE_MIGRATION = object : Migration(1, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d(TAG, "Safe migration: recreating tables while preserving data where possible")
                db.execSQL("""CREATE TABLE IF NOT EXISTS security_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    message TEXT NOT NULL,
                    simSlot INTEGER,
                    lac INTEGER,
                    cid INTEGER,
                    pdu TEXT
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS safe_zones (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    mccMnc TEXT NOT NULL,
                    lacTac TEXT NOT NULL,
                    trustedCellIds TEXT NOT NULL
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS cell_fingerprints (
                    cellId TEXT PRIMARY KEY NOT NULL,
                    lacTac TEXT NOT NULL,
                    arfcn TEXT NOT NULL,
                    dbm INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS imei_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    sim1OldImei TEXT NOT NULL,
                    sim1NewImei TEXT NOT NULL,
                    sim2OldImei TEXT NOT NULL,
                    sim2NewImei TEXT NOT NULL,
                    method TEXT NOT NULL,
                    success INTEGER NOT NULL,
                    deviceModel TEXT NOT NULL DEFAULT ''
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS imei_backup (
                    slotIndex INTEGER PRIMARY KEY NOT NULL,
                    originalImei TEXT NOT NULL,
                    backupTimestamp INTEGER NOT NULL,
                    deviceModel TEXT NOT NULL DEFAULT '',
                    isRestored INTEGER NOT NULL DEFAULT 0
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS cell_tower_map (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    cellId INTEGER NOT NULL,
                    lac INTEGER NOT NULL,
                    mcc INTEGER NOT NULL,
                    mnc INTEGER NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    signalStrength INTEGER NOT NULL,
                    networkType INTEGER NOT NULL,
                    isSuspicious INTEGER NOT NULL,
                    suspiciousReason TEXT,
                    isVerifiedOpenCelliD INTEGER NOT NULL DEFAULT 0,
                    anomalyScore REAL NOT NULL DEFAULT 0.0,
                    timestamp INTEGER NOT NULL
                )""")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "consul_imei_db"
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, SAFE_MIGRATION)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
