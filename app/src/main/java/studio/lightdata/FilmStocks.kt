package studio.lightdata

data class FilmRecord(val document: String, val brand: String, val iso: Int, val film: Film,
                      val experimentalEi: Boolean = false)

private const val I = "ILFORD"
private const val F = "Fujifilm"
private const val O = "Foma"

// These stocks have supporting product sheets and a usable exposure index.
// Their display shapes are shared emulsion-family models, deliberately identified in the UI.
private enum class Family(
    val info: String,
    val low: Double,
    val high: Double,
    val shape: List<Pair<Double, Double>>,
) {
    BW(
        "黑白负片",
        -4.0,
        5.0,
        listOf(-5.0 to .12, -4.0 to .20, -2.0 to .48, 0.0 to .86, 3.0 to 1.45, 6.0 to 1.94),
    ),
    COLOR(
        "彩色负片",
        -4.0,
        5.0,
        listOf(-5.0 to .45, -4.0 to .53, -2.0 to .77, 0.0 to 1.14, 3.0 to 1.75, 6.0 to 2.18),
    ),
    SLIDE(
        "反转片",
        -3.0,
        2.5,
        listOf(
            -4.0 to 3.3,
            -3.0 to 3.0,
            -1.0 to 1.59,
            0.0 to 1.0,
            1.0 to .56,
            2.5 to .23,
            3.5 to .15,
        ),
    ),
}

private fun group(brand: String, family: Family, process: String, rows: String) =
    rows.trimIndent().trim().lines().map { line ->
        val (id, ei, name) = line.trim().split(' ', limit = 3)
        FilmRecord(
            id,
            brand,
            ei.toInt(),
            Film(
                name,
                family.low,
                family.high,
                family.shape,
                process + " · " + family.info + "通用近似曲线和预警范围。原资料用于型号、曝光指数和工艺。",
                filmSourceUrl(id),
            ),
        )
    }

private val referenceStocks =
    group(
        I,
        Family.BW,
        "黑白显影，按原资料选择显影液",
        """
        11731318 50 PAN F PLUS
        03033b27 100 PAN 100
        03033b27 400 PAN 400
    """,
    ) +
        group(
            "Kentmere",
            Family.BW,
            "黑白显影",
            """
        8a1e74e8 100 PAN 100 · Kentmere
        b0b699da 200 PAN 200 · Kentmere
        ffe3f433 400 PAN 400 · Kentmere
    """,
        ) +
        group("HARMAN", Family.COLOR, "C-41", "c1be7692 200 Phoenix II") +
        group(
            F,
            Family.COLOR,
            "彩色负片工艺，详见原资料",
            """
        3131046d 100 FUJICOLOR 100
        9e3a5c63 200 FUJIFILM 200
        d90a217e 400 FUJIFILM 400
        175fb820 400 SUPERIA PREMIUM 400
        0395e7b5 800 SUPERIA X-TRA 800
        d37a8712 400 SUPERIA Venus 400
        94958d56 800 SUPERIA Venus 800
    """,
        ) +
        group(
            F,
            Family.SLIDE,
            "E-6 / CR-56",
            """
        5047abce 100 VELVIA 100
        a3b5b21b 100 VELVIA 100F
    """,
        ) +
        group(
            O,
            Family.BW,
            "黑白显影",
            """
        d895f8df 400 FOMA ORTHO 400
        fd9bdddd 320 RETROPAN 320 soft
    """,
        ) +
        group(O, Family.SLIDE, "FOMA 黑白反转工艺", "f5c74d3d 100 FOMAPAN R 100") +
        group(
            "ADOX",
            Family.BW,
            "黑白显影，按原资料选择显影液",
            """
        9228a1f0 100 CHS 100 II
        42ab5b1d 20 CMS 20 II PRO
        888806de 50 HR-50
    """,
        ) +
        group("Bergger", Family.BW, "黑白显影", "ae1825cf 400 PANCRO 400") +
        group(
            "Ferrania",
            Family.BW,
            "黑白显影",
            """
        e1877769 80 P30 ALPHA
        3258949e 160 P33
    """,
        ) +
        group(
            "Lomography",
            Family.COLOR,
            "C-41",
            """
        9147381f 100 Color Negative 100
        54632ba1 400 Color Negative 400
        e936ea9a 800 Color Negative 800
        288ca5c6 200 LomoChrome Purple · EI 100–400
        54630fb1 200 LomoChrome Metropolis · EI 100–400
        ae0bf32e 200 Lobster Redscale 110
        94fee373 100 REDSCALE XR · EI 50–200
        9d2e8cd1 200 Tiger 200 110
    """,
        ) +
        group(
            "Lomography",
            Family.BW,
            "黑白显影",
            """
        3beb7947 13 Babylon Kino 13
        1d574f6a 400 Berlin Kino 400
        a429411d 100 Earl Grey 100
        b01f746f 8 Fantôme Kino 8
        fdab01b0 400 Lady Grey 400
        18a8dba4 100 Orca B&W 110
        76934dab 100 Potsdam Kino 100
    """,
        ) +
        group(
            "Rollei",
            Family.BW,
            "黑白显影",
            """
        c58dfa2c 400 INFRARED 400
        b0db43fb 25 ORTHO 25 plus
        078695eb 400 RETRO 400S
        e81fad3e 80 RETRO 80S
        b26905a3 100 RPX 100
        e7c703af 25 RPX 25
        c3600619 400 RPX 400
        d4804249 200 SUPERPAN 200
    """,
        ) +
        group(
            "Film Washi",
            Family.BW,
            "按片基与乳剂选择原资料所列显影工艺",
            """
        d7637ad7 12 Washi A
        47cdb51f 500 Washi D
        81ded9be 3 Washi E
        eb91dbcf 100 Washi F
        a00c882c 80 Washi I
        1db2b4ae 100 Washi K
        0220728e 50 Washi S
        143bb964 100 Washi V
        bce258d1 25 Washi W
        0cd6e970 100 Washi Y
        4105ff00 400 Washi Z
    """,
        ) +
        group(
            "Wolfen",
            Family.COLOR,
            "C-41",
            """
        f1a4ae25 400 NC400
        7a108af7 400 NC500
    """,
        ) +
        group("Wolfen", Family.BW, "黑白显影", "5f5bf626 100 NP100") +
        group("乐凯", Family.BW, "黑白显影", "43bc9677 100 NEW SHD100")

val filmRecords: List<FilmRecord> by lazy {
    val measured = loadDensityCurves()
    val names = measured.mapTo(HashSet()) { it.film.name }
    measured + referenceStocks.filter { it.film.name !in names } + instantStocks + experimentalReversalStocks
}

val films: List<Film> by lazy { filmRecords.map { it.film } }
