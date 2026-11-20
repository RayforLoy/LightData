package studio.lightdata

// These display models are relative to the user's exposure index. Neither process source
// publishes reflection/transmission density measurements for its reversal workflow.
internal val experimentalReversalStocks: List<FilmRecord> = listOf(
    FilmRecord(
        "ra4-paper-reversal", "Fujifilm", 5,
        Film(
            "Crystal Archive · RA-4 相纸反转",
            -2.0, 2.0,
            listOf(-3.0 to 2.1, -2.0 to 1.85, -1.0 to 1.36, 0.0 to .92,
                1.0 to .51, 2.0 to .22, 3.0 to .11),
            "相机内直接曝光相纸。试拍采用预洗、避光干燥的 Crystal Archive；约 5400 K 晴天，85B + 50Y + 40M，测试 EI 5 已计入这组滤镜。黑白首显、再曝光、RA-4 彩显及漂定。曲线为反射正像示意，±2 EV 为试拍预警，实际宽容度须按纸批、滤镜和工艺验证。",
            filmSourceUrl("ra4-paper-reversal"),
        ), experimentalEi = true,
    ),
    FilmRecord(
        "aerocolor-d2460-e6", "Kodak", 64,
        Film(
            "AEROCOLOR IV 2460 · D2460 / E-6",
            -2.0, 2.0,
            listOf(-3.5 to 3.1, -2.0 to 2.55, -1.0 to 1.65, 0.0 to 1.05,
                1.0 to .62, 2.0 to .28, 3.0 to .15),
            "Kodak AS-2652 的原材为 0.10 mm ESTAR 航空彩负。此处采用 Elevanfilm D2460 专用镜前滤镜和 E-6 正冲。约 5500 K 时滤镜方建议独立测光 EI 64–80，本预设从 EI 64 起试，已计入滤镜；透镜测光参考 EI 100。曲线为透射正像示意，±2 EV 预警须用本批胶片包围曝光验证。",
            filmSourceUrl("aerocolor-d2460-e6"),
        ), experimentalEi = true,
    ),
)
