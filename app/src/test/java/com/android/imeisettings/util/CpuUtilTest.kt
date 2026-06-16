package com.android.imeisettings.util

import org.junit.Assert.*
import org.junit.Test

class CpuUtilTest {

    @Test
    fun `CpuType enum has all expected types`() {
        val types = CpuUtil.CpuType.values()
        assertTrue(types.contains(CpuUtil.CpuType.MEDIATEK))
        assertTrue(types.contains(CpuUtil.CpuType.QUALCOMM))
        assertTrue(types.contains(CpuUtil.CpuType.EXYNOS))
        assertTrue(types.contains(CpuUtil.CpuType.UNISOC))
        assertTrue(types.contains(CpuUtil.CpuType.KIRIN))
        assertTrue(types.contains(CpuUtil.CpuType.TENSOR))
        assertTrue(types.contains(CpuUtil.CpuType.UNKNOWN))
    }

    @Test
    fun `CpuType enum has 7 values`() {
        assertEquals(7, CpuUtil.CpuType.values().size)
    }

    @Test
    fun `getCpuName returns non-null string`() {
        val name = CpuUtil.getCpuName()
        assertNotNull(name)
        assertTrue(name.isNotEmpty())
    }

    @Test
    fun `getCpuType returns a valid type`() {
        val type = CpuUtil.getCpuType()
        assertNotNull(type)
        assertTrue(type in CpuUtil.CpuType.values())
    }
}
