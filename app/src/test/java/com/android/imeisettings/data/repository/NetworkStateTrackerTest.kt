package com.android.imeisettings.data.repository

import com.android.imeisettings.data.model.NetworkDetails
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NetworkStateTrackerTest {

    @Before
    fun setup() {
        NetworkStateTracker.resetThreatLevel()
    }

    @Test
    fun `initial threat level is 0 after reset`() {
        assertEquals(0, NetworkStateTracker.totalThreatLevel.value)
    }

    @Test
    fun `updateThreatLevel sets level`() {
        NetworkStateTracker.updateThreatLevel(50, "Test threat")
        assertEquals(50, NetworkStateTracker.totalThreatLevel.value)
        assertEquals("Test threat", NetworkStateTracker.threatReason.value)
    }

    @Test
    fun `forceForensicThreat overrides normal updates`() {
        NetworkStateTracker.forceForensicThreat(100, "Critical threat")
        assertEquals(100, NetworkStateTracker.totalThreatLevel.value)
        assertTrue(NetworkStateTracker.forensicThreatActive)

        NetworkStateTracker.updateThreatLevel(10, "Should be ignored")
        assertEquals(100, NetworkStateTracker.totalThreatLevel.value)
    }

    @Test
    fun `resetThreatLevel clears forensic mode`() {
        NetworkStateTracker.forceForensicThreat(100, "Critical")
        assertTrue(NetworkStateTracker.forensicThreatActive)

        NetworkStateTracker.resetThreatLevel()
        assertFalse(NetworkStateTracker.forensicThreatActive)
        assertEquals(0, NetworkStateTracker.totalThreatLevel.value)
        assertNull(NetworkStateTracker.threatReason.value)
    }

    @Test
    fun `updateSim1 sets network details`() {
        val details = NetworkDetails(
            operator = "TestOp",
            mccMnc = "250 / 01",
            cellId = "12345",
            signalDbm = -75
        )
        NetworkStateTracker.updateSim1(details)
        assertEquals("TestOp", NetworkStateTracker.networkDetailsSim1.value.operator)
        assertEquals(-75, NetworkStateTracker.networkDetailsSim1.value.signalDbm)
    }

    @Test
    fun `updateSim2 sets network details`() {
        val details = NetworkDetails(
            operator = "TestOp2",
            mccMnc = "247 / 05"
        )
        NetworkStateTracker.updateSim2(details)
        assertEquals("TestOp2", NetworkStateTracker.networkDetailsSim2.value.operator)
    }

    @Test
    fun `threat reason is null when no threat`() {
        NetworkStateTracker.resetThreatLevel()
        assertNull(NetworkStateTracker.threatReason.value)
    }

    @Test
    fun `default NetworkDetails has expected defaults`() {
        val details = NetworkDetails()
        assertEquals("N/A", details.operator)
        assertEquals("--- / ---", details.mccMnc)
        assertEquals("---", details.cellId)
        assertEquals(-120, details.signalDbm)
        assertEquals("NO SIM", details.voiceState)
    }
}
