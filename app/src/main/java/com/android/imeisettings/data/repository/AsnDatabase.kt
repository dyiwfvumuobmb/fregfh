package com.android.imeisettings.data.repository

object AsnDatabase {
    
    // Mapping: ASN -> Detailed Name
    val trustedAsns = mapOf(
        // --- GLOBAL / MULTINATIONAL ---
        "AS1273" to "Vodafone Group", "AS3209" to "Vodafone", "AS6730" to "Vodafone", "AS30722" to "Vodafone",
        "AS5511" to "Orange S.A.", "AS3215" to "Orange", "AS12479" to "Orange Spain",
        "AS12956" to "Telefonica", "AS3352" to "Telefonica Spain", "AS6805" to "Telefonica Germany (O2)",
        "AS3303" to "Swisscom", "AS6738" to "Swisscom",
        "AS1257" to "Tele2 AB", "AS1257" to "Tele2 Sweden",
        "AS2607" to "T-Mobile USA", "AS21928" to "T-Mobile", "AS3320" to "Deutsche Telekom",
        "AS137" to "GTT Communications", "AS174" to "Cogent Communications",
        "AS209" to "CenturyLink", "AS701" to "Verizon", "AS702" to "Verizon", "AS12322" to "Free / Iliad",

        // --- LATVIA ---
        "AS2588" to "BITE Latvija", "AS20910" to "BITE Latvija", "AS21016" to "BITE Latvija",
        "AS12847" to "LMT (Latvijas Mobilais Telefons)", "AS24921" to "LMT",
        "AS34326" to "CSC Telecom (SIA Master Telecom)", "AS43123" to "Lexico Telecom (SIA Master Telecom)",
        "AS60066" to "Tele2 SIA", "AS5518" to "SIA Tet", "AS6747" to "SIA Tet", "AS6906" to "SIA Tet",
        "AS47371" to "XOmobile Ltd.",

        // --- LITHUANIA ---
        "AS13194" to "UAB Bite Lietuva", "AS199527" to "UAB Bite Lietuva", "AS204746" to "UAB Bite Lietuva",
        "AS43482" to "Mediafon UAB", "AS5522" to "Telia Lietuva AB", "AS8764" to "Telia Lietuva AB", "AS42657" to "Telia Lietuva AB",

        // --- GERMANY ---
        "AS8638" to "1&1 Versatel", "AS8881" to "1&1 Versatel", "AS12313" to "1&1 Versatel",
        "AS12638" to "Telefonica Germany", "AS13184" to "Telefonica Germany",
        "AS2776" to "Deutsche Telekom", "AS2773" to "Deutsche Telekom", "AS2775" to "Deutsche Telekom",
        "AS3211" to "Vodafone GmbH", "AS6751" to "Vodafone GmbH", "AS42290" to "TelcoVillage GmbH",

        // --- RUSSIA ---
        "AS6731" to "MTS PJSC", "AS8359" to "MTS PJSC", "AS8580" to "MTS PJSC",
        "AS6850" to "MegaFon PJSC", "AS6854" to "MegaFon PJSC", "AS8263" to "MegaFon PJSC",
        "AS2599" to "Vimpelcom (Beeline)", "AS2766" to "Vimpelcom (Beeline)",
        "AS15378" to "Tele2 Russia (T2 Mobile)", "AS8752" to "AO ASVT",
        "AS44491" to "ZAO Aquafon-GSM", "AS51957" to "ZAO Aquafon-GSM",
        "AS210827" to "Aurora Telecom", "AS48442" to "Aurora Airlines",
        "AS38928" to "JSC GLONASS", "AS20576" to "Gazprom telecom", "AS25032" to "Gazprom telecom",
        "AS25513" to "MGTS PJSC", "AS57681" to "MGTS PJSC",
        "AS206673" to "Sberbank-Telecom", "AS35237" to "Sberbank of Russia", "AS41551" to "VTB Bank Mobile",

        // --- UKRAINE ---
        "AS12421" to "Kyivstar PJSC", "AS12530" to "Kyivstar PJSC", "AS15895" to "Kyivstar PJSC",
        "AS34058" to "lifecell LLC", "AS34702" to "Aktsiaselts WaveCom", "AS33845" to "Vodafone Ukraine",

        // --- USA ---
        "AS6934" to "AT&T", "AS7948" to "AT&T", "AS795" to "AT&T Services",
        "AS145" to "Verizon Business", "AS284" to "Verizon Business", "AS690" to "Verizon Business",
        "AS2824" to "T-Mobile USA", "AS2825" to "T-Mobile USA", "AS11202" to "T-Mobile USA",
        
        // --- ASIA / OCEANIA ---
        "AS4538" to "China Education and Research Network", "AS4134" to "China Telecom",
        "AS4808" to "China Unicom", "AS9808" to "China Mobile",
        "AS4755" to "TATA Communications", "AS9498" to "Bharti Airtel", "AS55836" to "Reliance Jio",
        "AS4760" to "HKT", "AS17621" to "CNC Group", "AS18101" to "Reliance",
        "AS2516" to "KDDI", "AS17676" to "Softbank", "AS4713" to "NTT Communications",

        // --- MIDDLE EAST / AFRICA ---
        "AS8452" to "TE-AS", "AS24863" to "Etisalat", "AS25019" to "Saudi Telecom",
        "AS37061" to "MTN South Africa", "AS36932" to "Vodacom South Africa",
        
        // --- BRAZIL / LATAM ---
        "AS10429" to "Telefonica Brasil", "AS18881" to "TELEMAR", "AS28573" to "NET Servicos",
        
        // --- CYPRUS ---
        "AS35432" to "Cablenet Communication", "AS6866" to "Cyprus Telecommunications Authority",
        "AS15805" to "EPIC LTD", "AS5425" to "Primetel PLC"
    )

    // Mapping: MCC_MNC -> List of Valid ASNs for this operator
    val operatorAsnMap = mapOf(
        "247_05" to listOf("AS2588", "AS20910", "AS21016"), // Bite LV
        "247_01" to listOf("AS12847", "AS24921"),         // LMT LV
        "247_02" to listOf("AS60066"),                    // Tele2 LV
        "246_02" to listOf("AS13194", "AS199527"),        // Bite LT
        "246_01" to listOf("AS5522", "AS8764", "AS42657"), // Telia LT
        "255_06" to listOf("AS34058", "AS34702"),         // lifecell UA
        "255_03" to listOf("AS12421", "AS12530", "AS15895"), // Kyivstar UA
        "255_01" to listOf("AS33845"),                    // Vodafone UA
        "250_01" to listOf("AS6731", "AS8359", "AS8580"), // MTS RU
        "250_02" to listOf("AS6850", "AS6854", "AS8263"), // MegaFon RU
        "250_99" to listOf("AS2599", "AS2766", "AS3216")  // Beeline RU
    )

    fun isTrusted(asn: String): Boolean {
        if (asn.isBlank()) return true
        return trustedAsns.containsKey(asn.uppercase())
    }

    /**
     * Checks if current ASN matches the SIM card operator
     */
    fun isAsnValidForSim(mccMnc: String, currentAsn: String, apiOrg: String = ""): Boolean {
        val key = mccMnc.replace(" / ", "_").replace(" ", "")
        val validAsns = operatorAsnMap[key]
        
        // 0. If currentAsn is empty or not provided, we can't validate (return true to avoid false alert)
        if (currentAsn.isBlank()) return true

        val upperAsn = currentAsn.uppercase()

        // 1. Check direct ASN match for this operator
        if (validAsns != null && validAsns.contains(upperAsn)) return true
        
        // 2. Fuzzy matching: if API organization name contains part of the operator name
        if (apiOrg.isNotEmpty()) {
            val org = apiOrg.lowercase()
            val keywords = listOf("vodafone", "orange", "t-mobile", "telefónica", "telefonica", "o2", "telia", "tele2", "telenor", "claro", "movistar", "airtel", "mtn", "beeline", "mts", "megafon", "kyivstar", "lifecell", "att", "verizon")
            for (kw in keywords) {
                if (org.contains(kw)) return true
            }
        }

        // 3. Special case: If we know the operator (validAsns is not null) 
        // and we reached here, it means the ASN is NOT in the list for this operator.
        // We only allow it if it's NOT a different KNOWN mobile operator's ASN.
        if (validAsns != null) {
            // Check if this ASN belongs to ANOTHER operator in our map
            val isAnotherOperatorAsn = operatorAsnMap.values.any { it.contains(upperAsn) }
            if (isAnotherOperatorAsn) return false
            
            // If it's a globally trusted ASN (but not a mobile operator in our map), allow it
            if (trustedAsns.containsKey(upperAsn)) return true
            
            // Otherwise, if it's some unknown ASN, we don't flag it as invalid to avoid false positives
            return true
        }

        // 4. If the MCC-MNC is unknown to us, we don't want to alert
        return true
    }
}
