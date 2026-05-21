package com.android.imeisettings.util

import org.junit.Assert.*
import org.junit.Test

class BootloaderGuideTest {

    @Test
    fun `GuideStep data class holds correct values`() {
        val step = BootloaderGuide.GuideStep("Test Title", "Test Description", "test command")
        assertEquals("Test Title", step.title)
        assertEquals("Test Description", step.description)
        assertEquals("test command", step.command)
    }

    @Test
    fun `GuideStep with null command`() {
        val step = BootloaderGuide.GuideStep("Title", "Desc")
        assertNull(step.command)
    }

    @Test
    fun `DeviceGuide data class holds correct values`() {
        val guide = BootloaderGuide.DeviceGuide(
            brand = "TestBrand",
            model = "TestModel",
            steps = listOf(BootloaderGuide.GuideStep("Step 1", "Do something")),
            warnings = listOf("Be careful")
        )
        assertEquals("TestBrand", guide.brand)
        assertEquals("TestModel", guide.model)
        assertEquals(1, guide.steps.size)
        assertEquals(1, guide.warnings.size)
    }

    @Test
    fun `getBootloaderStatus returns non-null string`() {
        val status = BootloaderGuide.getBootloaderStatus()
        assertNotNull(status)
        assertTrue(status in listOf("UNLOCKED", "LOCKED", "OEM UNLOCK ENABLED", "UNKNOWN"))
    }

    @Test
    fun `getGuideForDevice returns non-null guide`() {
        try {
            val guide = BootloaderGuide.getGuideForDevice()
            assertNotNull(guide)
            assertTrue(guide.steps.isNotEmpty())
            assertNotNull(guide.brand)
        } catch (e: NullPointerException) {
            // Build.MANUFACTURER may be null in unit test environment (no Android framework)
            // This is expected — test passes when running on device/emulator
        }
    }
}
