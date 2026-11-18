package studio.lightdata

import java.util.Locale
import kotlin.math.pow

enum class ReciprocityStatus {
    CORRECTED,
    UNCHANGED,
    UNPUBLISHED,
}

data class ReciprocityResult(
    val meteredSeconds: Double,
    val exposureSeconds: Double?,
    val status: ReciprocityStatus,
    val label: String,
    val sourceUrl: String?,
    val networkEstimate: Boolean = false,
)

private const val HP5_GUIDE =
    "https://www.ilfordphoto.com/wp/wp-content/uploads/2024/05/Reciprocity-Failure-Compensation-v2.pdf"
private const val PORTRA_GUIDE =
    "https://kodakprofessional.com/sites/default/files/wysiwyg/pro/resources/e4050_portra_400.pdf"
private const val E100_GUIDE =
    "https://kodakprofessional.com/sites/default/files/wysiwyg/pro/resources/e4000_ektachrome_100.pdf"

private val ilfordP =
    mapOf(
        "SFX 200" to 1.43,
        "PAN F PLUS" to 1.33,
        "DELTA 100" to 1.26,
        "DELTA 400" to 1.41,
        "DELTA 3200" to 1.33,
        "FP4 PLUS" to 1.26,
        "HP5 PLUS" to 1.31,
        "XP2 SUPER" to 1.31,
        "ORTHO PLUS" to 1.25,
        "PAN 100 · Kentmere" to 1.26,
        "PAN 400 · Kentmere" to 1.30,
    )
private val unchangedRanges = buildMap {
    for (name in
        listOf(
            "PORTRA 160",
            "PORTRA 400",
            "PORTRA 800",
            "PORTRA 160NC",
            "PORTRA 160VC",
            "PORTRA 400NC",
            "PORTRA 400VC",
            "EKTAR 100",
            "GOLD 200",
            "ULTRAMAX 400",
            "ULTRA MAX 800",
        )) put(name, .0001..1.0)
    for (name in listOf("VISION3 50D", "VISION3 200T", "VISION3 250D", "VISION3 500T")) put(
        name,
        .001..1.0,
    )
    for (name in listOf("FUJICOLOR C200", "PRO 160S", "SUPERIA X-TRA 400", "SUPERIA 1600")) put(
        name,
        .00025..2.0,
    )
    for (name in listOf("VELVIA 50", "PRO 400H", "SUPERIA REALA 100")) put(name, .00025..1.0)
    put("PROVIA 100F", .00025..128.0)
    put("ASTIA 100", .00025..32.0)
    put("ASTIA 100F", .00025..60.0)
    put("EKTACHROME E100", .0001..10.0)
    put("T-MAX 400", .0001..1.0)
    put("T-MAX 100", .001..0.1)
    for (name in listOf("TRI-X 320", "TRI-X 400")) put(name, .01..0.1)
    for (name in
        listOf(
            "FOMAPAN 100 Classic",
            "FOMAPAN 200 Creative",
            "FOMAPAN 400 Action",
            "RETROPAN 320 soft",
            "FOMA ORTHO 400",
        )) put(name, .001..0.5)
}

// Exact published shutter-time pairs. Lens-opening/filter instructions are kept separate.
private val correctedTables = buildMap {
    val foma400 = mapOf(1.0 to 1.5, 10.0 to 60.0, 100.0 to 800.0)
    for (name in listOf("FOMAPAN 400 Action", "RETROPAN 320 soft", "FOMA ORTHO 400")) put(
        name,
        foma400,
    )
    put("FOMAPAN 100 Classic", mapOf(1.0 to 2.0, 10.0 to 80.0, 100.0 to 1600.0))
    put("FOMAPAN 200 Creative", mapOf(1.0 to 3.0, 10.0 to 90.0, 100.0 to 1800.0))
    put("T-MAX 100", mapOf(10.0 to 15.0, 100.0 to 200.0))
    put("T-MAX 400", mapOf(100.0 to 300.0))
    val triX = mapOf(1.0 to 2.0, 10.0 to 50.0, 100.0 to 1200.0)
    put("TRI-X 400", triX)
    put("TRI-X 320", triX)
}
private val openingAdvice =
    mapOf(
        "T-MAX 100" to mapOf(.0001 to "开大 1/3 档", 1.0 to "开大 1/3 档"),
        "T-MAX 400" to mapOf(10.0 to "开大 1/3 档"),
        "PRO 400H" to mapOf(4.0 to "开大 1/2 档", 16.0 to "开大 1 档"),
        "PROVIA 100F" to mapOf(240.0 to "2.5G 滤镜，开大 1/3 档"),
        "VELVIA 50" to
            mapOf(
                4.0 to "5M 滤镜，开大 1/3 档",
                8.0 to "7.5M 滤镜，开大 1/2 档",
                16.0 to "10M 滤镜，开大 2/3 档",
                32.0 to "12.5M 滤镜，开大 1 档",
            ),
        "PANCRO 400" to mapOf(1.0 to "开大 1/2 档", 10.0 to "开大 1 档", 60.0 to "开大 2 档"),
        "EKTACHROME E100" to mapOf(120.0 to "日光加 CC10R，快门补偿未给出"),
    )

/** Keeps the ideal metered exposure independent of the film-camera shooting instruction. */
fun reciprocity(filmName: String, meteredSeconds: Double): ReciprocityResult {
    val power =
        ilfordP[filmName]
            ?: when (filmName) {
                "PAN 200 · Kentmere" -> 1.26
                "Phoenix II" -> 1.31
                else -> null
            }
    val supported =
        power != null ||
            filmName in unchangedRanges ||
            filmName in openingAdvice ||
            filmName == "ACROS II"
    val source =
        when {
            ilfordP.containsKey(filmName) -> HP5_GUIDE
            filmName == "PORTRA 400" -> PORTRA_GUIDE
            filmName == "EKTACHROME E100" -> E100_GUIDE
            supported ->
                filmRecords.find { it.film.name == filmName }?.let { filmSourceUrl(it.document) }
            else -> null
        }
    fun unknown(label: String) =
        ReciprocityResult(meteredSeconds, null, ReciprocityStatus.UNPUBLISHED, label, source)
    fun normal() =
        ReciprocityResult(
            meteredSeconds,
            meteredSeconds,
            ReciprocityStatus.UNCHANGED,
            "无需补偿",
            source,
        )
    fun corrected(t: Double, extra: String = ""): ReciprocityResult {
        if (!t.isFinite()) return unknown("超出可计算范围")
        val label = "%.1f".format(Locale.ROOT, t).removeSuffix(".0")
        return ReciprocityResult(
            meteredSeconds,
            t,
            ReciprocityStatus.CORRECTED,
            "补偿后 " + label + " 秒" + extra,
            source,
        )
    }
    if (!meteredSeconds.isFinite() || meteredSeconds <= 0) return unknown("快门时间无效")
    val online = onlineReciprocity[filmName]
    if (source == null && online == null) return unknown("无此预设的补偿资料")
    if (power != null) {
        if (meteredSeconds < .0001) return unknown("厂家未给出补偿值")
        if (meteredSeconds <= 1.0) return normal()
        // Both individual HARMAN data sheets graph up to 50 s metered. The
        // broader ILFORD guide gives no validated upper bound; 50 s is our
        // conservative display threshold, not a manufacturer's rating.
        val note = if (meteredSeconds > 50.0) " · 公式外推，建议试拍" else ""
        return corrected(meteredSeconds.pow(power), note)
    }
    if (filmName == "ACROS II") {
        if (meteredSeconds < 120.0) return normal()
        if (meteredSeconds <= 1000.0) return unknown("开大 1/2 档，保留测得快门")
    }
    if (filmName == "PANCRO 400" && meteredSeconds < 1.0) return normal()
    if (unchangedRanges[filmName]?.contains(meteredSeconds) == true) return normal()
    openingAdvice[filmName]?.get(meteredSeconds)?.let {
        return unknown(it)
    }
    correctedTables[filmName]?.get(meteredSeconds)?.let { t ->
        val development =
            if (filmName.startsWith("TRI-X"))
                when (meteredSeconds) {
                    1.0 -> " · 显影减 10%"
                    10.0 -> " · 显影减 20%"
                    100.0 -> " · 显影减 30%"
                    else -> ""
                }
            else ""
        return corrected(t, development)
    }
    if (online != null && meteredSeconds > 1.0) {
        val seconds = online.seconds(meteredSeconds, maxOf(1.0, unchangedRanges[filmName]?.endInclusive ?: 1.0))
        val result = corrected(seconds)
        if (result.exposureSeconds == null) return result
        val limit = online.points.lastOrNull()?.first
        val detail = if (limit != null && meteredSeconds > limit) " · 公式外推" else ""
        return result.copy(label = result.label + " · 数据来源网络" + detail,
            sourceUrl = online.url, networkEstimate = true)
    }
    return unknown("缺少此快门的补偿资料")
}
