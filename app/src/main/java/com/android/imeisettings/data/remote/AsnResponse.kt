package com.android.imeisettings.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AsnResponse(
    @param:Json(name = "asn") val asn: String? = null,
    @param:Json(name = "org") val org: String? = null,
    @param:Json(name = "country") val country: String? = null
)
