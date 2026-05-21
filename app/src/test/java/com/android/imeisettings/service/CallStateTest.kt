package com.android.imeisettings.service

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Test

class CallStateTest {
    @Test
    fun `test call state constants`() {
        assertEquals(0, TelephonyManager.CALL_STATE_IDLE)
        assertEquals(1, TelephonyManager.CALL_STATE_RINGING)
        assertEquals(2, TelephonyManager.CALL_STATE_OFFHOOK)
    }
}
