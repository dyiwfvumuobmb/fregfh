package com.android.imeisettings.util

import kotlin.random.Random

object ImeiGenerator {
    
    data class DeviceModel(val name: String, val tac: String)

    val models = listOf(
        // Samsung (Priority) — 2024-2026
        DeviceModel("Samsung S26 Ultra", "35812826"),
        DeviceModel("Samsung S26+", "35812726"),
        DeviceModel("Samsung S26", "35812626"),
        DeviceModel("Samsung S25 Ultra", "35812725"),
        DeviceModel("Samsung S25+", "35812625"),
        DeviceModel("Samsung S25", "35812525"),
        DeviceModel("Samsung S24 Ultra", "35812724"),
        DeviceModel("Samsung S24+", "35812624"),
        DeviceModel("Samsung S24", "35812524"),
        DeviceModel("Samsung S23 Ultra", "35812723"),
        DeviceModel("Samsung Z Fold 6", "35276106"),
        DeviceModel("Samsung Z Flip 6", "35276206"),
        DeviceModel("Samsung Z Fold 5", "35276105"),
        DeviceModel("Samsung Z Flip 5", "35276205"),
        DeviceModel("Samsung A56 5G", "35274156"),
        DeviceModel("Samsung A55", "35274155"),
        DeviceModel("Samsung A36 5G", "35274136"),
        DeviceModel("Samsung A35", "35274135"),
        DeviceModel("Samsung A16 5G", "35274116"),
        DeviceModel("Samsung A15", "35274115"),
        DeviceModel("Samsung S21", "35482411"),
        // Xiaomi (Priority) — 2024-2026
        DeviceModel("Xiaomi 16 Ultra", "86095016"),
        DeviceModel("Xiaomi 16 Pro", "86095116"),
        DeviceModel("Xiaomi 15 Ultra", "86095115"),
        DeviceModel("Xiaomi 15 Pro", "86095015"),
        DeviceModel("Xiaomi 15", "86095215"),
        DeviceModel("Xiaomi 14 Ultra", "86095014"),
        DeviceModel("Xiaomi 14 Pro", "86095114"),
        DeviceModel("Xiaomi 14", "86095214"),
        DeviceModel("Xiaomi 13 Pro", "86095013"),
        DeviceModel("Redmi Note 14 Pro+", "86153014"),
        DeviceModel("Redmi Note 14 Pro", "86153114"),
        DeviceModel("Redmi Note 13 Pro+", "86153013"),
        DeviceModel("Redmi Note 13 Pro", "86153113"),
        DeviceModel("Redmi Note 12", "86153012"),
        DeviceModel("POCO F7 Ultra", "86154107"),
        DeviceModel("POCO F7 Pro", "86154007"),
        DeviceModel("POCO F6 Pro", "86154006"),
        DeviceModel("POCO X7 Pro", "86154207"),
        DeviceModel("POCO X6 Pro", "86154106"),
        // Realme (Priority) — 2025-2026
        DeviceModel("Realme GT 7 Pro", "86907007"),
        DeviceModel("Realme GT 6", "86907006"),
        DeviceModel("Realme GT 5 Pro", "86907005"),
        DeviceModel("Realme 14 Pro+", "86907114"),
        DeviceModel("Realme 13 Pro+", "86907113"),
        DeviceModel("Realme 12 Pro+", "86907112"),
        DeviceModel("Realme 12 Pro", "86907212"),
        DeviceModel("Realme 11 Pro+", "86907111"),
        DeviceModel("Realme C67", "86907067"),
        DeviceModel("Realme Narzo 70 Pro", "86907070"),
        // Infinix (Transsion Holdings) — 2025-2026
        DeviceModel("Infinix GT 30 Pro", "35411430"),
        DeviceModel("Infinix GT 20 Pro", "35411420"),
        DeviceModel("Infinix NOTE 50 Pro+ 5G", "35411450"),
        DeviceModel("Infinix NOTE 40 Pro+ 5G", "35411440"),
        DeviceModel("Infinix NOTE 40 Pro", "35411340"),
        DeviceModel("Infinix ZERO 50 5G", "35411550"),
        DeviceModel("Infinix ZERO 40 5G", "35411540"),
        DeviceModel("Infinix ZERO 30 5G", "35411530"),
        DeviceModel("Infinix HOT 50 Pro+", "35411650"),
        DeviceModel("Infinix HOT 50 Pro", "35411550"),
        DeviceModel("Infinix NOTE 30 Pro", "35411330"),
        DeviceModel("Infinix NOTE 30 VIP", "35411230"),
        DeviceModel("Infinix GT 10 Pro", "35411410"),
        // TECNO (Transsion Holdings) — 2025-2026
        DeviceModel("TECNO CAMON 40 Pro 5G", "35417040"),
        DeviceModel("TECNO CAMON 30 Pro 5G", "35417030"),
        DeviceModel("TECNO PHANTOM V Fold 3", "35417030"),
        DeviceModel("TECNO PHANTOM V Fold 2", "35417020"),
        DeviceModel("TECNO POVA 7 Pro 5G", "35417070"),
        DeviceModel("TECNO POVA 6 Pro 5G", "35417060"),
        DeviceModel("TECNO SPARK 30 Pro+", "35417130"),
        DeviceModel("TECNO SPARK 20 Pro+", "35417120"),
        // Other popular — 2025-2026
        DeviceModel("Google Pixel 10 Pro", "35235510"),
        DeviceModel("Google Pixel 9a", "35235519"),
        DeviceModel("Google Pixel 9 Pro", "35235509"),
        DeviceModel("Google Pixel 8 Pro", "35235508"),
        DeviceModel("Google Pixel 6", "35235511"),
        DeviceModel("OnePlus 13", "35642913"),
        DeviceModel("OnePlus 12", "35642912"),
        DeviceModel("OnePlus 11", "35642911"),
        DeviceModel("Nothing Phone (3)", "35643003"),
        DeviceModel("Nothing Phone (2a) Plus", "35643002"),
        DeviceModel("Huawei Pura 70 Ultra", "86345270"),
        DeviceModel("Huawei P60 Pro", "86345260"),
        DeviceModel("iPhone 16 Pro Max", "35391216"),
        DeviceModel("iPhone 15 Pro", "35391215"),
        DeviceModel("iPhone 14 Pro", "35391214"),
        DeviceModel("Sony Xperia 1 VII", "35824317"),
        DeviceModel("Sony Xperia 1 VI", "35824316"),
        DeviceModel("vivo X200 Pro", "86417200"),
        DeviceModel("iQOO 13", "86417113"),
        DeviceModel("Nokia G60", "35521460"),
        DeviceModel("Motorola Edge 50 Ultra", "35948250"),
        DeviceModel("Motorola Edge 40", "35948240"),
        // 2025-2026 additional flagships
        DeviceModel("Samsung S27 Ultra", "35812827"),
        DeviceModel("Samsung Z Fold 7", "35276107"),
        DeviceModel("Samsung Z Flip 7", "35276207"),
        DeviceModel("Xiaomi 17 Ultra", "86095017"),
        DeviceModel("Xiaomi 17 Pro", "86095117"),
        DeviceModel("Google Pixel 11 Pro", "35235511"),
        DeviceModel("Google Pixel 10a", "35235510"),
        DeviceModel("OnePlus 14", "35642914"),
        DeviceModel("Nothing Phone (3a)", "35643003"),
        DeviceModel("Realme GT 8 Pro", "86907008"),
        DeviceModel("OPPO Find X8 Ultra", "86010208"),
        DeviceModel("OPPO Find X7 Ultra", "86010207"),
        DeviceModel("vivo X300 Pro", "86417300"),
        DeviceModel("iQOO 14", "86417114"),
        DeviceModel("Huawei Mate 70 Pro+", "86345370"),
        DeviceModel("Honor Magic 7 Pro", "86425107"),
        DeviceModel("Honor Magic 6 Pro", "86425106"),
        DeviceModel("Sony Xperia 1 VIII", "35824318"),
        DeviceModel("ASUS ROG Phone 9 Pro", "35217909"),
        DeviceModel("Nubia Z70 Ultra", "86712370"),
        DeviceModel("ZTE Axon 60 Ultra", "86712360"),
        DeviceModel("TECNO PHANTOM X3", "35417040"),
        DeviceModel("Infinix GT 40 Pro", "35411440")
    )

    fun generateImei(tac: String? = null): String {
        val useTac = if (tac != null && tac.length == 8) {
            tac
        } else {
            models.random().tac
        }
        val remainingLength = 14 - useTac.length
        val body = StringBuilder(useTac)
        for (i in 0 until remainingLength) {
            body.append(Random.nextInt(10))
        }
        val imei14 = body.toString()
        return imei14 + calculateLuhnCheckDigit(imei14)
    }

    fun generateImeiForModel(modelName: String): String? {
        val model = models.find { it.name.equals(modelName, ignoreCase = true) }
            ?: models.find { it.name.contains(modelName, ignoreCase = true) }
            ?: return null
        return generateImei(model.tac)
    }

    fun generateImeiForBrand(brand: String): String {
        val brandModels = models.filter { it.name.contains(brand, ignoreCase = true) }
        val selectedModel = if (brandModels.isNotEmpty()) brandModels.random() else models.random()
        return generateImei(selectedModel.tac)
    }

    fun getModelByTac(tac: String): DeviceModel? {
        return models.find { it.tac == tac }
    }

    fun getModelsForBrand(brand: String): List<DeviceModel> {
        return models.filter { it.name.contains(brand, ignoreCase = true) }
    }

    fun getAllBrands(): List<String> {
        return models.map { it.name.substringBefore(" ") }.distinct().sorted()
    }

    fun isValidImei(imei: String): Boolean {
        if (imei.length != 15) return false
        if (!imei.all { it.isDigit() }) return false
        val body = imei.substring(0, 14)
        val expected = calculateLuhnCheckDigit(body)
        val actual = imei[14] - '0'
        return expected == actual
    }

    private fun calculateLuhnCheckDigit(imei: String): Int {
        var sum = 0
        for (i in imei.indices.reversed()) {
            var n = imei[i] - '0'
            if (i % 2 != 0) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        val checkDigit = (10 - (sum % 10)) % 10
        return checkDigit
    }
}
