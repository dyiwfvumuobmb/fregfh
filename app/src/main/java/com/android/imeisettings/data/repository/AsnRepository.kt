package com.android.imeisettings.data.repository

import android.util.Log
import com.android.imeisettings.data.remote.AsnResponse
import com.android.imeisettings.data.remote.AsnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class AsnRepository {

    companion object {
        private const val TAG = "AsnRepository"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        private var cachedResponse: AsnResponse? = null
        private var cacheTimestamp = 0L

        fun getCached(): AsnResponse? {
            if (System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
                return cachedResponse
            }
            return null
        }

        private fun updateCache(response: AsnResponse) {
            cachedResponse = response
            cacheTimestamp = System.currentTimeMillis()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.ipify.org/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()

    private val service = retrofit.create(AsnService::class.java)

    suspend fun getAsnInfo(): AsnResponse? = withContext(Dispatchers.IO) {
        // Return cached data if still valid
        getCached()?.let { return@withContext it }

        // Try multiple IP providers in sequence
        val ip = getPublicIp()
        if (ip == null) {
            Log.e(TAG, "All IP providers failed")
            return@withContext null
        }

        // Try multiple ASN lookup providers
        val result = getAsnFromIp(ip)
        if (result != null) {
            updateCache(result)
        }
        result
    }

    private suspend fun getPublicIp(): String? {
        // Provider 1: api.ipify.org (Retrofit)
        try {
            val response = service.getIp()
            if (response.ip.isNotBlank()) {
                Log.d(TAG, "IP from ipify: ${response.ip}")
                return response.ip
            }
        } catch (e: Exception) {
            Log.w(TAG, "ipify failed: ${e.message}")
        }

        // Provider 2: api64.ipify.org (IPv4/IPv6 dual-stack)
        try {
            val request = Request.Builder().url("https://api64.ipify.org?format=json").build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val ip = json.optString("ip", "")
                    if (ip.isNotBlank()) {
                        Log.d(TAG, "IP from api64.ipify: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "api64.ipify failed: ${e.message}")
        }

        // Provider 3: ipinfo.io
        try {
            val request = Request.Builder().url("https://ipinfo.io/json").build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val ip = json.optString("ip", "")
                    if (ip.isNotBlank()) {
                        Log.d(TAG, "IP from ipinfo.io: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ipinfo.io failed: ${e.message}")
        }

        // Provider 4: ifconfig.me
        try {
            val request = Request.Builder().url("https://ifconfig.me/ip")
                .header("User-Agent", "curl/7.0")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val ip = resp.body?.string()?.trim() ?: ""
                    if (ip.isNotBlank() && ip.length < 46) {
                        Log.d(TAG, "IP from ifconfig.me: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ifconfig.me failed: ${e.message}")
        }

        return null
    }

    private suspend fun getAsnFromIp(ip: String): AsnResponse? {
        // Provider 1: ip.guide
        try {
            val guideResponse = service.getAsnInfo(ip)
            val net = guideResponse.network
            val asInfo = net?.autonomous_system
            if (asInfo != null) {
                return AsnResponse(
                    asn = "AS${asInfo.asn}",
                    org = asInfo.organization ?: net.organization ?: "Unknown",
                    country = net.country ?: ""
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ip.guide failed: ${e.message}")
        }

        // Provider 2: ipinfo.io (also provides ASN)
        try {
            val request = Request.Builder().url("https://ipinfo.io/$ip/json").build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val org = json.optString("org", "")
                    if (org.isNotBlank()) {
                        val parts = org.split(" ", limit = 2)
                        val asn = parts.getOrNull(0) ?: ""
                        val orgName = parts.getOrNull(1) ?: org
                        return AsnResponse(
                            asn = asn,
                            org = orgName,
                            country = json.optString("country", "")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ipinfo.io ASN lookup failed: ${e.message}")
        }

        // Provider 3: ip-api.com (free, no key required)
        try {
            val request = Request.Builder()
                .url("http://ip-api.com/json/$ip?fields=as,org,isp,country")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val asField = json.optString("as", "")
                    if (asField.isNotBlank()) {
                        val parts = asField.split(" ", limit = 2)
                        val asn = parts.getOrNull(0) ?: ""
                        val orgName = json.optString("org", "").ifBlank {
                            json.optString("isp", parts.getOrNull(1) ?: "Unknown")
                        }
                        return AsnResponse(
                            asn = asn,
                            org = orgName,
                            country = json.optString("country", "")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ip-api.com failed: ${e.message}")
        }

        return null
    }
}
