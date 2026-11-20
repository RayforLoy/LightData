package studio.lightdata

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishCatalogTest {
    private val catalog = EnglishCatalog(mapOf("测光" to "meter", "测光点" to "spot",
        "补偿" to "compensation", "上次读数" to "last reading", "人脸" to "Face"))

    @Test fun longestPhraseWinsAndInsertedValuesAreRetained() {
        assertEquals("spot #2", catalog.render("测光点 #2"))
        assertEquals("Face compensation +1 EV", catalog.render("人脸补偿 +1 EV"))
        assertEquals("#2 · last reading", catalog.render("#2 · 上次读数"))
        assertEquals("f/5.6 · ISO 160", catalog.render("f/5.6 · ISO 160"))
    }
}
