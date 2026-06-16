package com.android.imeisettings.data.remote

import org.junit.Assert.*
import org.junit.Test

class AsnResponseTest {

    @Test
    fun `AsnResponse creates with all fields`() {
        val response = AsnResponse(asn = "AS6731", org = "MTS PJSC", country = "RU")
        assertEquals("AS6731", response.asn)
        assertEquals("MTS PJSC", response.org)
        assertEquals("RU", response.country)
    }

    @Test
    fun `AsnResponse nullable fields default to null`() {
        val response = AsnResponse()
        assertNull(response.asn)
        assertNull(response.org)
        assertNull(response.country)
    }

    @Test
    fun `IpifyResponse holds IP`() {
        val response = IpifyResponse(ip = "1.2.3.4")
        assertEquals("1.2.3.4", response.ip)
    }

    @Test
    fun `IpGuideResponse parses network`() {
        val asn = IpGuideAsn(asn = 6731, organization = "MTS")
        val network = IpGuideNetwork(
            autonomous_system = asn,
            organization = "MTS PJSC",
            country = "RU"
        )
        val response = IpGuideResponse(network = network)
        assertEquals(6731, response.network?.autonomous_system?.asn)
        assertEquals("MTS", response.network?.autonomous_system?.organization)
        assertEquals("RU", response.network?.country)
    }

    @Test
    fun `IpGuideResponse handles null network`() {
        val response = IpGuideResponse()
        assertNull(response.network)
    }

    @Test
    fun `IpGuideNetwork handles null autonomous_system`() {
        val network = IpGuideNetwork(organization = "Test Org")
        assertNull(network.autonomous_system)
        assertEquals("Test Org", network.organization)
    }
}
