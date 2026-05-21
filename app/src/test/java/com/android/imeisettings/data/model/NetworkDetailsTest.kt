package com.android.imeisettings.data.model

import org.junit.Assert.*
import org.junit.Test

class NetworkDetailsTest {

    @Test
    fun `default NetworkDetails has correct defaults`() {
        val details = NetworkDetails()
        assertEquals("N/A", details.operator)
        assertEquals("--- / ---", details.mccMnc)
        assertEquals("N/A", details.asnInfo)
        assertEquals("", details.asnNumber)
        assertEquals("---", details.cellId)
        assertEquals("---", details.lacTac)
        assertEquals("---", details.pciPsc)
        assertEquals("---", details.arfcn)
        assertEquals("Auto", details.band)
        assertEquals(0, details.neighbors)
        assertEquals("OFF", details.roaming)
        assertEquals("N/A", details.encryption)
        assertEquals("DISCONNECTED", details.dataState)
        assertEquals("NO SIM", details.voiceState)
        assertEquals("N/A", details.networkType)
        assertEquals(-120, details.signalDbm)
    }

    @Test
    fun `NetworkDetails with custom values`() {
        val details = NetworkDetails(
            operator = "MTS",
            mccMnc = "250 / 01",
            cellId = "54321",
            signalDbm = -65,
            encryption = "A5/3",
            neighbors = 5,
            networkType = "LTE"
        )
        assertEquals("MTS", details.operator)
        assertEquals("250 / 01", details.mccMnc)
        assertEquals("54321", details.cellId)
        assertEquals(-65, details.signalDbm)
        assertEquals("A5/3", details.encryption)
        assertEquals(5, details.neighbors)
        assertEquals("LTE", details.networkType)
    }

    @Test
    fun `NetworkDetails copy preserves unmodified fields`() {
        val original = NetworkDetails(operator = "Test")
        val copy = original.copy(signalDbm = -50)
        assertEquals("Test", copy.operator)
        assertEquals(-50, copy.signalDbm)
        assertEquals("--- / ---", copy.mccMnc)
    }
}
