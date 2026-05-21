package com.android.imeisettings.util

import org.junit.Assert.*
import org.junit.Test

class PduAnalyzerTest {

    // ======================== Raw PDU Parsing ========================

    @Test
    fun `parseRawPdu returns null for too-short PDU`() {
        val shortPdu = byteArrayOf(0x00, 0x01, 0x02)
        assertNull(PduAnalyzer.parseRawPdu(shortPdu))
    }

    @Test
    fun `parseRawPdu parses valid SMS-DELIVER PDU`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0x00)
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertEquals(0, fields!!.scaLength)
        assertEquals(0, fields.tpMti)
        assertFalse(fields.tpUdhi)
        assertFalse(fields.tpSri)
        assertFalse(fields.tpMms)
        assertFalse(fields.tpRp)
        assertFalse(fields.tpLp)
        assertEquals(0, fields.pid)
        assertEquals(0, fields.dcs)
        assertEquals(5, fields.udl)
    }

    @Test
    fun `parseRawPdu detects UDHI flag`() {
        val pdu = buildTestPduWithFlags(firstOctet = 0x44, pid = 0x40)
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertTrue(fields!!.tpUdhi)
        assertEquals(0x40, fields.pid)
    }

    @Test
    fun `parseRawPdu detects SRI flag`() {
        val pdu = buildTestPduWithFlags(firstOctet = 0x20, pid = 0x00)
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertTrue(fields!!.tpSri)
    }

    @Test
    fun `parseRawPdu detects MMS flag`() {
        val pdu = buildTestPduWithFlags(firstOctet = 0x04, pid = 0x00)
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertTrue(fields!!.tpMms)
    }

    @Test
    fun `parseRawPdu detects RP flag`() {
        val pdu = buildTestPduWithFlags(firstOctet = 0x80.toInt(), pid = 0x00)
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertTrue(fields!!.tpRp)
    }

    @Test
    fun `parseRawPdu extracts SCA number`() {
        // SCA: length=4, type=0x91, digits +1234
        val pdu = byteArrayOf(
            0x04,                   // SCA length = 4
            0x91.toByte(),          // SCA type = international
            0x21, 0x43, 0xF5.toByte(), // SCA: +12345
            0x00,                   // First octet
            0x04,                   // OA length = 4
            0x91.toByte(),          // OA type
            0x21, 0x43,             // OA digits
            0x00,                   // PID
            0x00,                   // DCS
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS
            0x01,                   // UDL
            0x41                    // UD
        )
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertEquals(4, fields!!.scaLength)
        assertTrue(fields.scaNumber.startsWith("+"))
    }

    @Test
    fun `parseRawPdu handles MTI values`() {
        // MTI = 0b01 (SMS-SUBMIT)
        val pdu = buildTestPduWithFlags(firstOctet = 0x01, pid = 0x00)
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertEquals(1, fields!!.tpMti)
    }

    // ======================== analyzeRawPdu — Attack Detection ========================

    @Test
    fun `analyzeRawPdu detects Silent SMS`() {
        val pdu = buildTestPdu(pid = 0x40)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertEquals("SILENT_SMS", result.threatType)
        assertEquals(PduAnalyzer.ThreatLevel.CRITICAL, result.threatLevel)
        assertTrue(result.description.contains("Silent SMS"))
        assertTrue(result.detectedAttacks.contains("SILENT_SMS"))
    }

    @Test
    fun `analyzeRawPdu detects SIM Data Download`() {
        val pdu = buildTestPdu(pid = 0x7F)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("SIM_DATA_DOWNLOAD"))
        assertEquals(PduAnalyzer.ThreatLevel.CRITICAL, result.threatLevel)
    }

    @Test
    fun `analyzeRawPdu detects ME Data Download`() {
        val pdu = buildTestPdu(pid = 0x7E)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("ME_DATA_DOWNLOAD"))
        assertEquals(PduAnalyzer.ThreatLevel.HIGH, result.threatLevel)
    }

    @Test
    fun `analyzeRawPdu detects USAT Data`() {
        val pdu = buildTestPdu(pid = 0x7D)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("USAT_DATA"))
    }

    @Test
    fun `analyzeRawPdu detects SIM Toolkit OTA`() {
        for (pid in 0x41..0x47) {
            val pdu = buildTestPdu(pid = pid)
            val result = PduAnalyzer.analyzeRawPdu(pdu)
            assertTrue("PID 0x${"%02x".format(pid)} should be threat", result.isThreat)
            assertTrue(result.detectedAttacks.contains("SIM_TOOLKIT_OTA"))
        }
    }

    @Test
    fun `analyzeRawPdu detects Flash SMS via DCS`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0x10)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("FLASH_SMS"))
        assertEquals(PduAnalyzer.ThreatLevel.MEDIUM, result.threatLevel)
    }

    @Test
    fun `analyzeRawPdu detects FBS suspect via signal strength`() {
        val pdu = buildTestPdu(pid = 0x00)
        val result = PduAnalyzer.analyzeRawPdu(pdu, signalDbm = -30)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("FBS_SUSPECT"))
        assertTrue(result.isFbsSuspect)
        assertTrue(result.description.contains("FBS"))
    }

    @Test
    fun `analyzeRawPdu FBS threshold is -40 dBm from RILDefender`() {
        assertEquals(-40, PduAnalyzer.FBS_RSSI_THRESHOLD)

        // At threshold: not FBS
        val pdu1 = buildTestPdu(pid = 0x00)
        val result1 = PduAnalyzer.analyzeRawPdu(pdu1, signalDbm = -40)
        assertFalse(result1.isFbsSuspect)

        // Above threshold: FBS
        val pdu2 = buildTestPdu(pid = 0x00)
        val result2 = PduAnalyzer.analyzeRawPdu(pdu2, signalDbm = -39)
        assertTrue(result2.isFbsSuspect)
    }

    @Test
    fun `analyzeRawPdu normal SMS is not a threat`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0x00)
        val result = PduAnalyzer.analyzeRawPdu(pdu, signalDbm = -80)
        assertFalse(result.isThreat)
        assertNull(result.threatType)
        assertFalse(result.isFbsSuspect)
        assertTrue(result.detectedAttacks.isEmpty())
        assertEquals(PduAnalyzer.ThreatLevel.NONE, result.threatLevel)
    }

    @Test
    fun `analyzeRawPdu sets honeypot when threat detected`() {
        val pdu = buildTestPdu(pid = 0x40)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.honeypotTriggered)
    }

    @Test
    fun `analyzeRawPdu no honeypot for normal SMS`() {
        val pdu = buildTestPdu(pid = 0x00)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertFalse(result.honeypotTriggered)
    }

    @Test
    fun `analyzeRawPdu detects multiple attacks simultaneously`() {
        // Silent SMS + SIM Data Download style — PID 0x40 with FBS signal
        val pdu = buildTestPdu(pid = 0x40)
        val result = PduAnalyzer.analyzeRawPdu(pdu, signalDbm = -20)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.size >= 2)
        assertTrue(result.detectedAttacks.contains("SILENT_SMS"))
        assertTrue(result.detectedAttacks.contains("FBS_SUSPECT"))
        assertEquals(PduAnalyzer.ThreatLevel.CRITICAL, result.threatLevel)
    }

    // ======================== DCS Analysis ========================

    @Test
    fun `analyzeRawPdu detects DCS Message Waiting Discard`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0xC0)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("MSG_WAITING_DISCARD"))
    }

    @Test
    fun `analyzeRawPdu detects DCS Message Waiting Store`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0xD0)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("MSG_WAITING_STORE"))
    }

    @Test
    fun `analyzeRawPdu detects DCS Message Waiting UCS2`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0xE0)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("MSG_WAITING_UCS2"))
    }

    @Test
    fun `analyzeRawPdu detects SIM specific DCS`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0xF2) // Group 1111, class 2
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.detectedAttacks.contains("SIM_SPECIFIC_DCS"))
        assertEquals(PduAnalyzer.ThreatLevel.HIGH, result.threatLevel)
    }

    @Test
    fun `analyzeRawPdu identifies GSM7 encoding via DCS`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0x00)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertEquals(PduAnalyzer.DcsEncoding.GSM7, result.dcsEncoding)
    }

    @Test
    fun `analyzeRawPdu identifies UCS2 encoding via DCS`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0x08)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertEquals(PduAnalyzer.DcsEncoding.UCS2, result.dcsEncoding)
    }

    @Test
    fun `analyzeRawPdu identifies Binary encoding via DCS`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0x04)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertEquals(PduAnalyzer.DcsEncoding.BINARY, result.dcsEncoding)
    }

    // ======================== GSM 7-bit Decoding (GSM 03.38) ========================

    @Test
    fun `decodeGsm7BitDefault decodes Hello`() {
        // "Hello" in GSM 7-bit packed: C8 32 9B FD 06
        val data = byteArrayOf(0xC8.toByte(), 0x32, 0x9B.toByte(), 0xFD.toByte(), 0x06)
        val result = PduAnalyzer.decodeGsm7BitDefault(data, 5)
        assertEquals("Hello", result)
    }

    @Test
    fun `decodeGsm7BitDefault handles special GSM characters`() {
        // '@' is GSM char 0x00
        val data = byteArrayOf(0x00)
        val result = PduAnalyzer.decodeGsm7BitDefault(data, 1)
        assertEquals("@", result)
    }

    @Test
    fun `decodeGsm7BitDefault handles extended characters via escape`() {
        // Euro sign '€' = escape(0x1B) + 0x65 (101)
        // In GSM 7-bit packed:
        // char0 = 0x1B (escape) = 0011011 (7 bits)
        // char1 = 0x65 (101)   = 1100101 (7 bits)
        // Packed into bytes (LSB first):
        // byte0 = char0[6:0] | char1[0] << 7 = 0001_1011 | 1_0000000 = 1001_1011 = 0x9B
        // byte1 = char1[6:1] = 011_0010 = 0x32
        val data = byteArrayOf(0x9B.toByte(), 0x32)
        val result = PduAnalyzer.decodeGsm7BitDefault(data, 2)
        assertEquals("€", result)
    }

    @Test
    fun `decodeGsm7BitDefault decodes digits correctly`() {
        // "123" in GSM 7-bit: 0x31=49='1', 0x32=50='2', 0x33=51='3'
        // Packed: 31 D9 0C
        val data = byteArrayOf(0x31, 0xD9.toByte(), 0x0C)
        val result = PduAnalyzer.decodeGsm7BitDefault(data, 3)
        assertEquals("123", result)
    }

    @Test
    fun `decodeGsm7BitDefault handles empty data`() {
        val result = PduAnalyzer.decodeGsm7BitDefault(byteArrayOf(), 0)
        assertEquals("", result)
    }

    @Test
    fun `analyzeRawPdu decodes body for standard SMS`() {
        val pdu = buildTestPdu(pid = 0x00, dcs = 0x00)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertEquals("Hello", result.body)
    }

    // ======================== UCS-2 Decoding ========================

    @Test
    fun `analyzeRawPdu decodes UCS2 body`() {
        // UCS-2 encoded "Hi" = 0x00 0x48 0x00 0x69
        val pdu = byteArrayOf(
            0x00,                   // SCA length = 0
            0x00,                   // First octet
            0x04,                   // OA length = 4
            0x91.toByte(),          // OA type
            0x21, 0x43,             // OA digits
            0x00,                   // PID
            0x08,                   // DCS = UCS2
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS
            0x04,                   // UDL = 4 bytes
            0x00, 0x48, 0x00, 0x69  // "Hi" in UCS-2
        )
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertEquals("Hi", result.body)
        assertEquals(PduAnalyzer.DcsEncoding.UCS2, result.dcsEncoding)
    }

    // ======================== SIMJacker Detection ========================

    @Test
    fun `analyzeRawPdu detects SIMJacker via UDH port`() {
        // PDU with UDHI flag and 16-bit port header pointing to SIMJacker port 0x0B84 (2948)
        val pdu = byteArrayOf(
            0x00,                   // SCA length = 0
            0x44,                   // First octet: UDHI=1
            0x04,                   // OA length = 4
            0x91.toByte(),          // OA type
            0x21, 0x43,             // OA digits
            0x00,                   // PID
            0x00,                   // DCS
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS
            0x0C,                   // UDL
            // UDH: 6 bytes header
            0x05,                   // UDH length = 5
            0x05,                   // IEI = 16-bit port addressing
            0x03,                   // IE length = 3 (should be 4 for proper 16-bit ports, but testing detection)
            0x0B.toByte(), 0x84.toByte(), // Dest port = 0x0B84 = 2948 (SIMJacker)
            0x00,                   // Src port high byte
            // Body
            0x41, 0x42, 0x43, 0x44, 0x45 // Dummy data
        )
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        // Should detect either via UDH port extraction or UDH analysis
        assertTrue(
            result.detectedAttacks.any { it.contains("SIMJACKER") || it.contains("WAP_PUSH") }
        )
    }

    // ======================== Proactive SIM STK Detection ========================

    @Test
    fun `analyzeRawPdu detects STK SEND_SMS command`() {
        // PDU with UDHI and proactive SIM command tag 0xD0 containing SEND_SMS (0x13)
        val pdu = byteArrayOf(
            0x00,                   // SCA length = 0
            0x44,                   // First octet: UDHI=1
            0x04,                   // OA length = 4
            0x91.toByte(),          // OA type
            0x21, 0x43,             // OA digits
            0x7F,                   // PID = SIM Data Download
            0x00,                   // DCS
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS
            0x0C,                   // UDL
            // UDH
            0x02,                   // UDH length = 2
            0x70,                   // IEI = SIM Toolkit Security
            0x00,                   // IE length = 0
            // Proactive SIM command
            0xD0.toByte(),          // Proactive SIM command tag
            0x04,                   // Command length
            0x01,                   // Command details tag
            0x01,                   // Command details length
            0x13,                   // Command type = SEND_SMS (0x13)
            0x00                    // Extra
        )
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertEquals(PduAnalyzer.ThreatLevel.CRITICAL, result.threatLevel)
    }

    // ======================== WAP Push Decoding ========================

    @Test
    fun `decodeWapPush handles null data`() {
        assertEquals("Empty WAP Data", PduAnalyzer.decodeWapPush(null))
    }

    @Test
    fun `decodeWapPush handles empty array`() {
        assertEquals("Empty WAP Data", PduAnalyzer.decodeWapPush(byteArrayOf()))
    }

    @Test
    fun `decodeWapPush extracts text from Push data`() {
        val data = byteArrayOf(
            0x01,       // Transaction ID
            0x06,       // PDU Type = Push
            0x01,       // Headers length
            0x03,       // Content-Type text/plain
            0x48, 0x65, 0x6C, 0x6C, 0x6F  // "Hello"
        )
        val result = PduAnalyzer.decodeWapPush(data)
        assertTrue(result.contains("Push"))
        assertTrue(result.contains("Hello"))
    }

    @Test
    fun `decodeWapPush handles Confirmed Push type`() {
        val data = byteArrayOf(
            0x01,       // Transaction ID
            0x07,       // PDU Type = Confirmed Push
            0x01,       // Headers length
            0x03,       // Content-Type
            0x41        // 'A'
        )
        val result = PduAnalyzer.decodeWapPush(data)
        assertTrue(result.contains("Confirmed Push"))
    }

    @Test
    fun `decodeWapPush handles binary-only data`() {
        val data = byteArrayOf(0x01, 0x06, 0x01, 0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        val result = PduAnalyzer.decodeWapPush(data)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `decodeWapPush detects Service Loading threat`() {
        // WSP Push with Service Loading content type 0x30
        val data = byteArrayOf(
            0x01,       // Transaction ID
            0x06,       // PDU Type = Push
            0x01,       // Headers length
            0x30,       // Content-Type = application/vnd.wap.sl (Service Loading)
            0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x00, // "Hello" + padding
            0xB0.toByte() // Extra byte with 0xb0 to trigger OTA provisioning detection
        )
        val result = PduAnalyzer.decodeWapPush(data)
        // Should detect Service Loading via contentTypeContainsSl or OTA provisioning via 0xb0
        assertTrue(result.contains("Service Loading") || result.contains("OTA Provisioning"))
    }

    // ======================== BCD Address Decoding ========================

    @Test
    fun `parseRawPdu decodes international number format`() {
        val pdu = byteArrayOf(
            0x00,                   // SCA length = 0
            0x00,                   // First octet
            0x0A,                   // OA length = 10 digits
            0x91.toByte(),          // OA type = international (0x91)
            0x71, 0x83.toByte(), 0x94.toByte(), 0x50, 0x87.toByte(),
            0x00,                   // PID
            0x00,                   // DCS
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS
            0x00                    // UDL
        )
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertTrue(fields!!.oaDigits.startsWith("+"))
    }

    @Test
    fun `parseRawPdu decodes national number format`() {
        val pdu = byteArrayOf(
            0x00,                   // SCA length = 0
            0x00,                   // First octet
            0x04,                   // OA length = 4 digits
            0x81.toByte(),          // OA type = national (0x81)
            0x21, 0x43,             // OA digits: 1234
            0x00,                   // PID
            0x00,                   // DCS
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS
            0x00                    // UDL
        )
        val fields = PduAnalyzer.parseRawPdu(pdu)
        assertNotNull(fields)
        assertFalse(fields!!.oaDigits.startsWith("+"))
        assertTrue(fields.oaDigits.contains("1234"))
    }

    // ======================== UDH Analysis ========================

    @Test
    fun `analyzeRawPdu detects SIM Toolkit Security UDH`() {
        val pdu = byteArrayOf(
            0x00,                   // SCA length = 0
            0x44,                   // First octet: UDHI=1
            0x04,                   // OA length = 4
            0x91.toByte(),          // OA type
            0x21, 0x43,             // OA digits
            0x00,                   // PID
            0x00,                   // DCS
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS
            0x05,                   // UDL
            // UDH with SIM Toolkit Security IEI (0x70)
            0x02,                   // UDH length = 2
            0x70,                   // IEI = SIM Toolkit Security
            0x00,                   // IE length = 0
            0x41, 0x42             // Dummy data
        )
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
        assertTrue(result.hasUdh)
    }

    @Test
    fun `analyzeRawPdu detects SMS PP Download UDH`() {
        val pdu = byteArrayOf(
            0x00, 0x44, 0x04, 0x91.toByte(), 0x21, 0x43,
            0x00, 0x00,
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x05,
            0x02, 0x71, 0x00,       // UDH: IEI 0x71 = SMS_PP_DOWNLOAD
            0x41, 0x42
        )
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.isThreat)
    }

    @Test
    fun `analyzeRawPdu allows normal concatenated SMS`() {
        val pdu = byteArrayOf(
            0x00, 0x44, 0x04, 0x91.toByte(), 0x21, 0x43,
            0x00, 0x00,
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x08,
            // UDH: concatenated SMS (IEI 0x00) — should NOT be a threat
            0x05, 0x00, 0x03, 0x01, 0x02, 0x01,
            0x41, 0x42
        )
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        // Concatenated SMS UDH is normal, not a threat by itself
        assertFalse(result.detectedAttacks.any { it.contains("SIM") || it.contains("WAP") })
    }

    // ======================== Port Extraction ========================

    @Test
    fun `analyzeRawPdu extracts 16-bit ports from UDH`() {
        val pdu = byteArrayOf(
            0x00, 0x44, 0x04, 0x91.toByte(), 0x21, 0x43,
            0x00, 0x00,
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x0C,
            // UDH with 16-bit port addressing (IEI 0x05)
            0x06,                   // UDH length = 6
            0x05,                   // IEI = 16-bit port
            0x04,                   // IE length = 4
            0x23, 0xF0.toByte(),    // Dest port = 0x23F0 = 9200
            0x00, 0x00,             // Src port = 0
            0x41, 0x42, 0x43, 0x44, 0x45 // Data
        )
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertNotNull(result.udhPorts)
        assertEquals(9200, result.udhPorts!!.destPort)
        assertTrue(result.udhPorts!!.is16bit)
        // Port 9200 = WAP connection → should be threat
        assertTrue(result.isThreat)
    }

    // ======================== Threat Level Ordering ========================

    @Test
    fun `threat level ordering is correct`() {
        assertTrue(PduAnalyzer.ThreatLevel.CRITICAL > PduAnalyzer.ThreatLevel.HIGH)
        assertTrue(PduAnalyzer.ThreatLevel.HIGH > PduAnalyzer.ThreatLevel.MEDIUM)
        assertTrue(PduAnalyzer.ThreatLevel.MEDIUM > PduAnalyzer.ThreatLevel.LOW)
        assertTrue(PduAnalyzer.ThreatLevel.LOW > PduAnalyzer.ThreatLevel.NONE)
    }

    // ======================== Edge Cases ========================

    @Test
    fun `analyzeRawPdu handles corrupt PDU gracefully`() {
        val result = PduAnalyzer.analyzeRawPdu(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        assertFalse(result.isThreat)
        assertTrue(result.description.contains("Failed to parse"))
    }

    @Test
    fun `analyzeRawPdu handles all zeros PDU`() {
        val pdu = ByteArray(20) { 0x00 }
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        // All zeros = valid parse, no threat
        assertNotNull(result)
    }

    @Test
    fun `analyzeRawPdu includes sender from OA field`() {
        val pdu = buildTestPdu(pid = 0x00)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertTrue(result.sender.isNotEmpty())
        assertTrue(result.sender.contains("1234") || result.sender.startsWith("+"))
    }

    @Test
    fun `analyzeRawPdu pduHex is correct hex encoding`() {
        val pdu = buildTestPdu(pid = 0x00)
        val result = PduAnalyzer.analyzeRawPdu(pdu)
        assertEquals(pdu.size * 2, result.pduHex.length)
        assertTrue(result.pduHex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ======================== Helper ========================

    private fun buildTestPdu(pid: Int = 0x00, dcs: Int = 0x00): ByteArray {
        return byteArrayOf(
            0x00,                   // SCA length = 0
            0x00,                   // First octet: SMS-DELIVER, no UDHI
            0x04,                   // OA length = 4 digits
            0x91.toByte(),          // OA type = international
            0x21, 0x43,             // OA digits: 1234
            pid.toByte(),           // PID
            dcs.toByte(),           // DCS
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS (7 bytes)
            0x05,                   // UDL = 5
            0xC8.toByte(), 0x32, 0x9B.toByte(), 0xFD.toByte(), 0x06  // "Hello" GSM 7-bit
        )
    }

    private fun buildTestPduWithFlags(firstOctet: Int, pid: Int = 0x00, dcs: Int = 0x00): ByteArray {
        return byteArrayOf(
            0x00,                   // SCA length = 0
            firstOctet.toByte(),    // First octet with custom flags
            0x04,                   // OA length = 4 digits
            0x91.toByte(),          // OA type = international
            0x21, 0x43,             // OA digits: 1234
            pid.toByte(),           // PID
            dcs.toByte(),           // DCS
            0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, // SCTS (7 bytes)
            0x05,                   // UDL = 5
            0xC8.toByte(), 0x32, 0x9B.toByte(), 0xFD.toByte(), 0x06  // "Hello" GSM 7-bit
        )
    }
}
