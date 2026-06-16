package com.android.imeisettings.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenCelliD API client for crowdsourced cell tower database comparison.
 * Compares local cell observations against known legitimate towers.
 */
object OpenCelliDClient {

    private const val TAG = "CONSUL_OPENCELLID"
    private const val BASE_URL = "https://opencellid.org/ajax/searchCell.php"
    private const val UNWIRED_URL = "https://us1.unwiredlabs.com/v2/process.php"
    private const val CACHE_TTL_MS = 3_600_000L // 1 hour cache

    data class CellTowerInfo(
        val mcc: Int,
        val mnc: Int,
        val lac: Int,
        val cellId: Long,
        val lat: Double,
        val lon: Double,
        val range: Int,
        val samples: Int,
        val isVerified: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class CellVerificationResult(
        val isKnown: Boolean,
        val distanceMeters: Double?,
        val expectedRange: Int?,
        val suspiciousReasons: List<String>
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, CellTowerInfo?>>()

    fun getCacheKey(mcc: Int, mnc: Int, lac: Int, cellId: Long): String {
        return "$mcc-$mnc-$lac-$cellId"
    }

    suspend fun lookupCell(
        mcc: Int,
        mnc: Int,
        lac: Int,
        cellId: Long,
        apiKey: String? = null
    ): CellTowerInfo? = withContext(Dispatchers.IO) {
        val key = getCacheKey(mcc, mnc, lac, cellId)

        // Check cache
        cache[key]?.let { (timestamp, info) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                return@withContext info
            }
        }

        try {
            val result = queryOpenCelliD(mcc, mnc, lac, cellId, apiKey)
            cache[key] = System.currentTimeMillis() to result
            result
        } catch (e: Exception) {
            Log.e(TAG, "Cell lookup failed: ${e.message}")
            cache[key] = System.currentTimeMillis() to null
            null
        }
    }

    private fun queryOpenCelliD(
        mcc: Int,
        mnc: Int,
        lac: Int,
        cellId: Long,
        apiKey: String?
    ): CellTowerInfo? {
        val urlStr = if (apiKey != null) {
            "$UNWIRED_URL"
        } else {
            "$BASE_URL?mcc=$mcc&mnc=$mnc&lac=$lac&cell_id=$cellId"
        }

        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        try {
            if (apiKey != null) {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = JSONObject().apply {
                    put("token", apiKey)
                    put("radio", "gsm")
                    put("mcc", mcc)
                    put("mnc", mnc)
                    put("cells", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("lac", lac)
                            put("cid", cellId)
                        })
                    })
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)

                if (json.has("lat") && json.has("lon")) {
                    return CellTowerInfo(
                        mcc = mcc,
                        mnc = mnc,
                        lac = lac,
                        cellId = cellId,
                        lat = json.getDouble("lat"),
                        lon = json.getDouble("lon"),
                        range = json.optInt("accuracy", json.optInt("range", 0)),
                        samples = json.optInt("samples", 0),
                        isVerified = true
                    )
                }
            }
        } finally {
            conn.disconnect()
        }
        return null
    }

    suspend fun verifyCellTower(
        mcc: Int,
        mnc: Int,
        lac: Int,
        cellId: Long,
        observedLat: Double?,
        observedLon: Double?,
        observedSignalDbm: Int?,
        apiKey: String? = null
    ): CellVerificationResult = withContext(Dispatchers.IO) {
        val reasons = mutableListOf<String>()

        val knownTower = lookupCell(mcc, mnc, lac, cellId, apiKey)

        if (knownTower == null) {
            reasons.add("Cell tower not found in OpenCelliD database")
            return@withContext CellVerificationResult(
                isKnown = false,
                distanceMeters = null,
                expectedRange = null,
                suspiciousReasons = reasons
            )
        }

        var distance: Double? = null
        if (observedLat != null && observedLon != null) {
            distance = haversineDistance(
                observedLat, observedLon,
                knownTower.lat, knownTower.lon
            )

            if (knownTower.range > 0 && distance > knownTower.range * 3) {
                reasons.add("Tower appears ${distance.toInt()}m away but expected range is ${knownTower.range}m")
            }
        }

        if (knownTower.samples < 3) {
            reasons.add("Low verification count (${knownTower.samples} samples)")
        }

        if (observedSignalDbm != null && observedSignalDbm > -30) {
            reasons.add("Signal strength abnormally high: ${observedSignalDbm}dBm")
        }

        CellVerificationResult(
            isKnown = true,
            distanceMeters = distance,
            expectedRange = knownTower.range,
            suspiciousReasons = reasons
        )
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    fun clearCache() {
        cache.clear()
    }

    fun getCachedTowers(): List<CellTowerInfo> {
        return cache.values.mapNotNull { it.second }
    }
}
