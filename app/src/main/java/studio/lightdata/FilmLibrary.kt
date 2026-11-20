package studio.lightdata

private data class ManualSource(val kind: String, val link: String)

private object FilmManuals {
    val byId: Map<String, ManualSource> by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/studio/lightdata/manual-links.tsv"))
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.associate { row ->
                val (id, kind, url) = row.split('\t', limit = 3)
                id to ManualSource(kind, url)
            }
        }
    }
}

internal fun filmSourceUrl(documentId: String): String = FilmManuals.byId.getValue(documentId).link

internal fun filmSourceLabel(documentId: String): String = when (FilmManuals.byId.getValue(documentId).kind) {
    "process" -> "工艺试拍资料 ↗"
    "manual" -> "型号手册 ↗"
    "record" -> "手册出处 ↗"
    else -> "查找手册 ↗"
}
