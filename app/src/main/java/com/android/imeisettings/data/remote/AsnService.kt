package com.android.imeisettings.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path

interface AsnService {
    @GET("https://api.ipify.org/?format=json")
    suspend fun getIp(): IpifyResponse

    @GET("https://ip.guide/{ip}")
    suspend fun getAsnInfo(@Path("ip") ip: String): IpGuideResponse
}

@JsonClass(generateAdapter = true)
data class IpifyResponse(val ip: String)

@JsonClass(generateAdapter = true)
data class IpGuideResponse(
    val network: IpGuideNetwork? = null
)

@JsonClass(generateAdapter = true)
data class IpGuideNetwork(
    @param:Json(name = "autonomous_system") val autonomous_system: IpGuideAsn? = null,
    val organization: String? = null,
    val country: String? = null
)

@JsonClass(generateAdapter = true)
data class IpGuideAsn(
    val asn: Int? = null,
    val organization: String? = null
)
