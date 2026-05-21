package com.android.imeisettings.util

import android.os.Build
import android.util.Log
import java.io.File

object CpuUtil {
    private const val TAG = "CpuUtil"

    @Volatile private var cachedCpuName: String? = null
    @Volatile private var cachedCpuType: CpuType? = null

    enum class CpuType {
        MEDIATEK, QUALCOMM, EXYNOS, UNISOC, KIRIN, TENSOR, UNKNOWN
    }

    data class CpuInfo(
        val type: CpuType,
        val name: String,
        val generation: Int = 0
    )

    fun getCpuName(): String {
        cachedCpuName?.let { return it }
        return try {
            val hardware = Build.HARDWARE
            val board = Build.BOARD
            val platform = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                getProp("ro.board.platform")
            }
            val manufacturer = Build.SOC_MANUFACTURER.lowercase()

            val cpuInfo = getCpuInfoFromProc()
            val combined = "$platform $hardware $cpuInfo $board $manufacturer".lowercase()

            when {
                combined.contains("tensor") || combined.contains("gs101") || combined.contains("gs201") ||
                combined.contains("gs301") || combined.contains("gs401") || combined.contains("gs501") ||
                combined.contains("zuma") || combined.contains("zumapro") ||
                combined.contains("gs601") -> "Google Tensor ($platform)"

                combined.contains("kirin") || combined.contains("hi3") || combined.contains("hisilicon") ||
                combined.contains("balong") -> "HiSilicon Kirin ($platform)"

                combined.contains("mt") || combined.contains("mediatek") || combined.contains("dimensity") ||
                combined.contains("mt6991") || combined.contains("mt6990") ||
                combined.contains("mt6989") || combined.contains("mt6988") ||
                combined.contains("mt6985") || combined.contains("mt6983") ->
                    "MediaTek ($platform)"

                combined.contains("qcom") || combined.contains("qualcomm") || combined.contains("snapdragon") ||
                combined.contains("sm8") || combined.contains("sm7") || combined.contains("sm6") ||
                combined.contains("sm4") || combined.contains("msm") || combined.contains("sdm") ||
                combined.contains("pineapple") || combined.contains("sun") ||
                combined.contains("8 elite") || combined.contains("8s elite") ||
                combined.contains("sm8750") || combined.contains("sm8735") ||
                combined.contains("sm8650") || combined.contains("sm7675") ||
                combined.contains("sm7550") || combined.contains("sm6550") ||
                combined.contains("niobe") || combined.contains("volcano") ||
                combined.contains("lanai") || combined.contains("blair") ||
                combined.contains("kodiak") || combined.contains("sm8850") ||
                combined.contains("8s gen 4") || combined.contains("8 gen 4") ||
                combined.contains("7+ gen 4") || combined.contains("7s gen 4") ||
                combined.contains("6 gen 4") || combined.contains("8 elite gen 2") -> "Qualcomm ($platform)"

                combined.contains("exynos") || combined.contains("s5e") || combined.contains("s5p") ||
                (combined.contains("samsung") &&
                (combined.contains("universal") || combined.contains("samsungexynos"))) -> "Exynos ($platform)"

                combined.contains("sp98") || combined.contains("sp97") || combined.contains("unisoc") ||
                combined.contains("spreadtrum") || combined.contains("ums") || combined.contains("t6") ||
                combined.contains("t7") || combined.contains("t8") || combined.contains("t9") ||
                combined.contains("t616") || combined.contains("t612") || combined.contains("t710") ||
                combined.contains("t760") || combined.contains("t770") || combined.contains("t820") ||
                combined.contains("t900") || combined.contains("t930") || combined.contains("t950") ||
                combined.contains("t7535") || combined.contains("t8200") ->
                    "Unisoc ($platform)"

                else -> platform.ifEmpty { hardware }.ifEmpty { "Unknown" }
            }.also { cachedCpuName = it }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun getCpuType(): CpuType {
        cachedCpuType?.let { return it }
        val name = getCpuName().lowercase()
        return when {
            name.contains("tensor") || name.contains("gs101") || name.contains("gs201") ||
            name.contains("gs301") || name.contains("gs401") || name.contains("gs501") ||
            name.contains("gs601") ||
            name.contains("zuma") || name.contains("zumapro") -> CpuType.TENSOR

            name.contains("kirin") || name.contains("hisilicon") -> CpuType.KIRIN

            name.contains("mediatek") || name.contains("mt") || name.contains("dimensity") ->
                CpuType.MEDIATEK

            name.contains("qualcomm") || name.contains("qcom") || name.contains("snapdragon") ||
            name.contains("sm8") || name.contains("sm7") || name.contains("sm6") ||
            name.contains("sm4") || name.contains("pineapple") || name.contains("sun") ||
            name.contains("8 elite") || name.contains("8s elite") ||
            name.contains("niobe") || name.contains("volcano") ||
            name.contains("lanai") || name.contains("blair") ||
            name.contains("kodiak") || name.contains("sm8850") ||
            name.contains("8s gen 4") || name.contains("8 gen 4") ||
            name.contains("7+ gen 4") || name.contains("7s gen 4") ||
            name.contains("6 gen 4") || name.contains("8 elite gen 2") -> CpuType.QUALCOMM

            name.contains("exynos") || name.contains("s5e") || name.contains("s5p") -> CpuType.EXYNOS

            name.contains("unisoc") || name.contains("spreadtrum") || name.contains("sp98") ||
            name.contains("ums") || name.contains("t930") || name.contains("t950") ||
            name.contains("t7525") || name.contains("t8200") || name.contains("t770") ||
            name.contains("t760") || name.contains("t820") || name.contains("t900") -> CpuType.UNISOC

            else -> CpuType.UNKNOWN
        }.also { cachedCpuType = it }
    }

    private fun getProp(propName: String): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop $propName")
            process.inputStream.bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            ""
        }
    }

    fun getSnapdragonGeneration(): Int {
        val name = getCpuName().lowercase()
        return when {
            name.contains("sm8850") || name.contains("8 elite gen 2") || name.contains("kodiak") -> 5
            name.contains("sm8750") || name.contains("8 gen 4") || name.contains("niobe") -> 4
            name.contains("sm8735") || name.contains("8s gen 4") || name.contains("8s elite") -> 4
            name.contains("sm8650") || name.contains("8 gen 3") || name.contains("8 elite") || name.contains("pineapple") -> 3
            name.contains("sm8550") || name.contains("8 gen 2") -> 2
            name.contains("sm8450") || name.contains("8 gen 1") -> 1
            else -> 0
        }
    }

    fun getDimensityGeneration(): Int {
        val name = getCpuName().lowercase()
        return when {
            name.contains("mt6991") || name.contains("9500") -> 9500
            name.contains("mt6990") || name.contains("8400") -> 8400
            name.contains("mt6989") || name.contains("9400") -> 9400
            name.contains("mt6988") || name.contains("9300") -> 9300
            name.contains("mt6985") || name.contains("9200") -> 9200
            name.contains("mt6983") || name.contains("9000") -> 9000
            else -> 0
        }
    }

    fun isModernFlagship(): Boolean {
        val snapGen = getSnapdragonGeneration()
        val dimGen = getDimensityGeneration()
        val name = getCpuName().lowercase()
        return snapGen >= 3 || dimGen >= 9200 ||
            name.contains("gs401") || name.contains("gs501") || name.contains("gs601") ||
            name.contains("exynos 2500") || name.contains("exynos 2400") ||
            name.contains("exynos 2600") || name.contains("exynos 2700") ||
            name.contains("s5e9965") || name.contains("sm8850")
    }

    private fun getCpuInfoFromProc(): String {
        val file = File("/proc/cpuinfo")
        if (!file.exists()) return ""
        return try {
            file.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.contains("Hardware") || line!!.contains("Processor") || line!!.contains("model name")) {
                        return@use line!!
                    }
                }
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
