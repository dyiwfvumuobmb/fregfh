package com.android.imeisettings.util

import org.junit.Assert.*
import org.junit.Test

class XposedCheckTest {

    @Test
    fun `isModuleActive returns false by default`() {
        assertFalse(XposedCheck.isModuleActive())
    }
}
