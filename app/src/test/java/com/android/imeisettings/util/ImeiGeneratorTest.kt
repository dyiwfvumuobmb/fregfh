package com.android.imeisettings.util

import org.junit.Assert.*
import org.junit.Test

class ImeiGeneratorTest {

    @Test
    fun `generated IMEI has 15 digits`() {
        val imei = ImeiGenerator.generateImei()
        assertEquals(15, imei.length)
        assertTrue(imei.all { it.isDigit() })
    }

    @Test
    fun `generated IMEI passes Luhn checksum`() {
        repeat(100) {
            val imei = ImeiGenerator.generateImei()
            assertTrue("IMEI $imei failed Luhn check", isValidLuhn(imei))
        }
    }

    @Test
    fun `generated IMEI with TAC prefix starts correctly`() {
        val tac = "35391210"
        val imei = ImeiGenerator.generateImei(tac)
        assertTrue(imei.startsWith(tac))
        assertEquals(15, imei.length)
        assertTrue(isValidLuhn(imei))
    }

    @Test
    fun `all predefined TACs generate valid IMEIs`() {
        ImeiGenerator.models.forEach { model ->
            val imei = ImeiGenerator.generateImei(model.tac)
            assertTrue("${model.name}: IMEI $imei failed Luhn", isValidLuhn(imei))
            assertTrue("${model.name}: wrong TAC", imei.startsWith(model.tac))
        }
    }

    @Test
    fun `generated IMEI without TAC starts with 35 or 86`() {
        repeat(50) {
            val imei = ImeiGenerator.generateImei()
            val prefix = imei.substring(0, 2)
            assertTrue("Unexpected prefix: $prefix", prefix == "35" || prefix == "86")
        }
    }

    @Test
    fun `short TAC generates correct length IMEI`() {
        val imei = ImeiGenerator.generateImei("35")
        assertEquals(15, imei.length)
        assertTrue(imei.startsWith("35"))
        assertTrue(isValidLuhn(imei))
    }

    @Test
    fun `models list is not empty`() {
        assertTrue(ImeiGenerator.models.isNotEmpty())
    }

    @Test
    fun `all TACs are 8 digits`() {
        ImeiGenerator.models.forEach { model ->
            assertEquals("${model.name} TAC length", 8, model.tac.length)
            assertTrue("${model.name} TAC has non-digits", model.tac.all { it.isDigit() })
        }
    }

    @Test
    fun `multiple generated IMEIs are unique`() {
        val imeis = (1..100).map { ImeiGenerator.generateImei() }.toSet()
        assertTrue("Expected mostly unique IMEIs", imeis.size > 90)
    }

    @Test
    fun `isValidImei accepts valid IMEI`() {
        val imei = ImeiGenerator.generateImei()
        assertTrue("Generated IMEI should pass validation: $imei", ImeiGenerator.isValidImei(imei))
    }

    @Test
    fun `isValidImei rejects too short IMEI`() {
        assertFalse(ImeiGenerator.isValidImei("12345678"))
    }

    @Test
    fun `isValidImei rejects too long IMEI`() {
        assertFalse(ImeiGenerator.isValidImei("1234567890123456"))
    }

    @Test
    fun `isValidImei rejects non-digit IMEI`() {
        assertFalse(ImeiGenerator.isValidImei("12345678901234A"))
    }

    @Test
    fun `isValidImei rejects wrong checksum`() {
        val imei = ImeiGenerator.generateImei()
        val lastDigit = imei.last() - '0'
        val wrongDigit = (lastDigit + 1) % 10
        val wrongImei = imei.substring(0, 14) + wrongDigit
        assertFalse("IMEI with wrong checksum should fail: $wrongImei", ImeiGenerator.isValidImei(wrongImei))
    }

    @Test
    fun `isValidImei validates all generated TACs`() {
        ImeiGenerator.models.forEach { model ->
            val imei = ImeiGenerator.generateImei(model.tac)
            assertTrue("${model.name}: IMEI $imei should be valid", ImeiGenerator.isValidImei(imei))
        }
    }

    @Test
    fun `models include Samsung S24 and S25`() {
        val modelNames = ImeiGenerator.models.map { it.name }
        assertTrue("Missing Samsung S24 Ultra", modelNames.any { it.contains("S24") })
        assertTrue("Missing Samsung S25", modelNames.any { it.contains("S25") })
    }

    @Test
    fun `models include Xiaomi 14 and 15`() {
        val modelNames = ImeiGenerator.models.map { it.name }
        assertTrue("Missing Xiaomi 14", modelNames.any { it.contains("Xiaomi 14") })
        assertTrue("Missing Xiaomi 15", modelNames.any { it.contains("Xiaomi 15") })
    }

    @Test
    fun `models include Realme 12 and 13`() {
        val modelNames = ImeiGenerator.models.map { it.name }
        assertTrue("Missing Realme 12", modelNames.any { it.contains("Realme 12") })
        assertTrue("Missing Realme 13", modelNames.any { it.contains("Realme 13") })
    }

    @Test
    fun `models list has at least 30 devices`() {
        assertTrue("Expected at least 30 models, got ${ImeiGenerator.models.size}", ImeiGenerator.models.size >= 30)
    }

    private fun isValidLuhn(number: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in number.length - 1 downTo 0) {
            var n = number[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }
}
