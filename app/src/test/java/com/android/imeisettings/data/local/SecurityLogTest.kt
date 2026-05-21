package com.android.imeisettings.data.local

import org.junit.Assert.*
import org.junit.Test

class SecurityLogTest {

    @Test
    fun `SecurityLog creates with required fields`() {
        val log = SecurityLog(
            timestamp = 1000L,
            type = "ALERT",
            message = "Silent SMS detected"
        )
        assertEquals(0L, log.id)
        assertEquals(1000L, log.timestamp)
        assertEquals("ALERT", log.type)
        assertEquals("Silent SMS detected", log.message)
        assertNull(log.simSlot)
        assertNull(log.lac)
        assertNull(log.cid)
        assertNull(log.pdu)
    }

    @Test
    fun `SecurityLog creates with all fields`() {
        val log = SecurityLog(
            id = 5,
            timestamp = 2000L,
            type = "WARNING",
            message = "Encryption downgrade",
            simSlot = 0,
            lac = 12345,
            cid = 67890,
            pdu = "deadbeef"
        )
        assertEquals(5L, log.id)
        assertEquals(0, log.simSlot)
        assertEquals(12345, log.lac)
        assertEquals(67890, log.cid)
        assertEquals("deadbeef", log.pdu)
    }

    @Test
    fun `SecurityLog equality`() {
        val log1 = SecurityLog(id = 1, timestamp = 1000L, type = "INFO", message = "Test")
        val log2 = SecurityLog(id = 1, timestamp = 1000L, type = "INFO", message = "Test")
        assertEquals(log1, log2)
    }

    @Test
    fun `SafeZone creates correctly`() {
        val zone = SafeZone(
            name = "Home",
            mccMnc = "25001",
            lacTac = "1234",
            trustedCellIds = "100,200,300"
        )
        assertEquals("Home", zone.name)
        assertEquals("25001", zone.mccMnc)
        assertEquals("1234", zone.lacTac)
        assertTrue(zone.trustedCellIds.contains("200"))
    }

    @Test
    fun `CellFingerprint creates correctly`() {
        val fp = CellFingerprint(
            cellId = "12345",
            lacTac = "678",
            arfcn = "1500",
            dbm = -80,
            timestamp = 3000L
        )
        assertEquals("12345", fp.cellId)
        assertEquals("678", fp.lacTac)
        assertEquals("1500", fp.arfcn)
        assertEquals(-80, fp.dbm)
    }
}
