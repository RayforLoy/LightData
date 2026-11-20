package studio.lightdata

import kotlin.math.log10

private const val chartMethod =
    "第 3 页图 8，同版曲线见 mini 与 WIDE 两份手册。5500 K，1/100 s，25°C，曝光后 24 小时测量。取绿色反射密度；中灰取片白反射率的 18%，预警取两端各内缩 0.10 D。"

private const val squareMethod =
    "ISO 来自 SQ1 厂家手册。未取得 SQUARE 特性图，借用 instax mini 的曲线与预警范围作通用近似。"

private const val impossibleMethod =
    "ISO 据 Impossible 原包装。未取得本型号的密度图；预览使用通用近似的反射正片模型，预警暂取中灰上下各两档。过期胶片应按实物试拍。"

private fun instaxChart(): List<Pair<Double,Double>> {
    val stream = requireNotNull(Film::class.java.getResourceAsStream("instax-density.csv"))
    return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.drop(1).map { line ->
            val (logH,density) = line.split(',')
            logH.toDouble() to density.toDouble()
        }.toList()
    }
}

internal val instantStocks: List<FilmRecord> by lazy {
    val chart = instaxChart()
    // Invert the descending trace to locate the reflective gray and the two warning densities.
    val inverse = chart.asReversed().map { (logH,d) -> d to logH }
    val white = chart.last().second
    val grayLogH = densityAt(inverse,white-log10(.18))
    fun stops(logH: Double) = (logH-grayLogH)/log10(2.0)
    val curve = chart.map { (logH,d) -> stops(logH) to d }
    val low = stops(densityAt(inverse,chart.first().second-.10))
    val high = stops(densityAt(inverse,white+.10))

    fun instax(id: String, name: String, method: String) = FilmRecord(id,"Fujifilm",800,
        Film(name,low,high,curve,method,filmSourceUrl(id)))

    fun impossible(id: String,name: String,iso: Int): FilmRecord {
        // Unit-gamma reflection print with a finite paper white and black.
        // These display assumptions are intentionally independent of historic Polaroid emulsions.
        val model = (-8..8).map { halfStop ->
            val ev = halfStop/2.0
            ev to (-log10(.18)-ev*log10(2.0)).coerceIn(.04,1.65)
        }
        return FilmRecord(id,"The Impossible Project",iso,
            Film(name,-2.0,2.0,model,impossibleMethod,filmSourceUrl(id)))
    }

    listOf(
        instax("instax-mini","instax mini Color","AF3-076E · $chartMethod"),
        instax("instax-square","instax SQUARE Color",squareMethod),
        instax("instax-wide","instax WIDE Color","AF3-077E · $chartMethod"),
        impossible("tip-color600","Impossible Color Film for Polaroid 600",640),
        impossible("tip-colorsx70","Impossible Color Film for Polaroid SX-70",160),
    )
}
