package com.android.imeisettings.data.repository

import org.junit.Assert.*
import org.junit.Test

class AsnDatabaseTest {

    @Test
    fun `trustedAsns is not empty`() {
        assertTrue(AsnDatabase.trustedAsns.isNotEmpty())
    }

    @Test
    fun `isTrusted returns true for known ASN`() {
        assertTrue(AsnDatabase.isTrusted("AS6731"))
        assertTrue(AsnDatabase.isTrusted("AS12421"))
        assertTrue(AsnDatabase.isTrusted("AS2588"))
    }

    @Test
    fun `isTrusted returns false for unknown ASN`() {
        assertFalse(AsnDatabase.isTrusted("AS99999"))
        assertFalse(AsnDatabase.isTrusted("AS00001"))
    }

    @Test
    fun `isTrusted is case-insensitive`() {
        assertTrue(AsnDatabase.isTrusted("as6731"))
        assertTrue(AsnDatabase.isTrusted("As12421"))
    }

    @Test
    fun `isTrusted returns true for blank ASN`() {
        assertTrue(AsnDatabase.isTrusted(""))
        assertTrue(AsnDatabase.isTrusted("  "))
    }

    @Test
    fun `isAsnValidForSim returns true for correct operator ASN`() {
        assertTrue(AsnDatabase.isAsnValidForSim("247_05", "AS2588"))
        assertTrue(AsnDatabase.isAsnValidForSim("250_01", "AS6731"))
        assertTrue(AsnDatabase.isAsnValidForSim("255_03", "AS12421"))
    }

    @Test
    fun `isAsnValidForSim returns false for wrong operator ASN`() {
        assertFalse(AsnDatabase.isAsnValidForSim("247_05", "AS12421"))
        assertFalse(AsnDatabase.isAsnValidForSim("250_01", "AS2588"))
    }

    @Test
    fun `isAsnValidForSim returns true for unknown MCC-MNC`() {
        assertTrue(AsnDatabase.isAsnValidForSim("999_99", "AS12345"))
    }

    @Test
    fun `isAsnValidForSim handles format with spaces`() {
        assertTrue(AsnDatabase.isAsnValidForSim("247 / 05", "AS2588"))
    }

    @Test
    fun `operatorAsnMap contains Baltic operators`() {
        assertNotNull(AsnDatabase.operatorAsnMap["247_05"]) // Bite LV
        assertNotNull(AsnDatabase.operatorAsnMap["247_01"]) // LMT LV
        assertNotNull(AsnDatabase.operatorAsnMap["246_02"]) // Bite LT
    }

    @Test
    fun `operatorAsnMap contains Russian operators`() {
        assertNotNull(AsnDatabase.operatorAsnMap["250_01"]) // MTS
        assertNotNull(AsnDatabase.operatorAsnMap["250_02"]) // MegaFon
        assertNotNull(AsnDatabase.operatorAsnMap["250_99"]) // Beeline
    }

    @Test
    fun `operatorAsnMap contains Ukrainian operators`() {
        assertNotNull(AsnDatabase.operatorAsnMap["255_06"]) // lifecell
        assertNotNull(AsnDatabase.operatorAsnMap["255_03"]) // Kyivstar
        assertNotNull(AsnDatabase.operatorAsnMap["255_01"]) // Vodafone
    }

    @Test
    fun `trustedAsns contains major country operators`() {
        // Germany
        assertTrue(AsnDatabase.trustedAsns.containsKey("AS3209")) // Vodafone DE
        assertTrue(AsnDatabase.trustedAsns.containsKey("AS2776")) // Deutsche Telekom

        // USA
        assertTrue(AsnDatabase.trustedAsns.containsKey("AS6934")) // AT&T
        assertTrue(AsnDatabase.trustedAsns.containsKey("AS145"))  // Verizon
        assertTrue(AsnDatabase.trustedAsns.containsKey("AS2824")) // T-Mobile

        // Cyprus
        assertTrue(AsnDatabase.trustedAsns.containsKey("AS6866")) // CyTA
    }
}
