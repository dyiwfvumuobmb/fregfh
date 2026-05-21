package com.android.imeisettings.util

import org.junit.Assert.*
import org.junit.Test

class ImeiChangerUtilTest {

    @Test
    fun `PreCheckResult data class holds correct values`() {
        val result = ImeiChangerUtil.PreCheckResult(
            isReady = true,
            cpuInfo = "MediaTek MT6893",
            statusMessage = "READY: All systems operational",
            details = "UID: 1000, Props: true, MTK NVRAM: OK"
        )
        assertTrue(result.isReady)
        assertEquals("MediaTek MT6893", result.cpuInfo)
        assertTrue(result.statusMessage.contains("READY"))
    }

    @Test
    fun `PreCheckResult with not ready state`() {
        val result = ImeiChangerUtil.PreCheckResult(
            isReady = false,
            cpuInfo = "Unknown",
            statusMessage = "FATAL: Not a System App",
            details = "UID: 10123, Props: false"
        )
        assertFalse(result.isReady)
        assertTrue(result.statusMessage.contains("FATAL"))
    }

    @Test
    fun `UpdateResult OK status`() {
        val result = UpdateResult("OK")
        assertEquals("OK", result.status)
        assertNull(result.newWifiMac)
    }

    @Test
    fun `UpdateResult Error with message`() {
        val result = UpdateResult("Error", newWifiMac = "Hardware blocked")
        assertEquals("Error", result.status)
        assertEquals("Hardware blocked", result.newWifiMac)
    }
}
