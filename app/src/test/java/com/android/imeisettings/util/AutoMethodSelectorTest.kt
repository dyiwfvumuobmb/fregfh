package com.android.imeisettings.util

import org.junit.Assert.*
import org.junit.Test

class AutoMethodSelectorTest {

    @Test
    fun `MethodResult data class holds correct values`() {
        val result = AutoMethodSelector.MethodResult(true, "TestMethod")
        assertTrue(result.success)
        assertEquals("TestMethod", result.methodName)
    }

    @Test
    fun `MethodResult with failure`() {
        val result = AutoMethodSelector.MethodResult(false, "Failed Method")
        assertFalse(result.success)
        assertEquals("Failed Method", result.methodName)
    }
}
