package studio.lightdata

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/** Translate at presentation boundaries; persistent presets and camera identifiers keep their original values. */
internal class EnglishCatalog(entries: Map<String, String>) {
    private val full = entries.filterKeys { it.isNotEmpty() }
    private val prefixes = full.keys.groupBy { it.first() }.mapValues { (_, keys) -> keys.sortedByDescending { it.length } }

    fun render(text: String): String {
        full[text]?.let { return it }
        if (text.none { it.code in 0x3400..0x9fff }) return text
        return buildString {
            var index = 0
            while (index < text.length) {
                val key = prefixes[text[index]]?.firstOrNull { text.startsWith(it, index) }
                if (key == null) append(text[index++])
                else {
                    val value = full.getValue(key)
                    if (lastOrNull()?.isLetterOrDigit() == true && value.firstOrNull()?.isLetterOrDigit() == true) append(' ')
                    append(value)
                    index += key.length
                    if (lastOrNull()?.isLetterOrDigit() == true && text.getOrNull(index)?.isLetterOrDigit() == true) append(' ')
                }
            }
        }
    }
}

internal object UiLanguage {
    private var english by mutableStateOf(false)
    val usesEnglish get() = english
    var selection by mutableStateOf("system")
        private set
    private var catalog = EnglishCatalog(emptyMap())

    fun initialize(a: MainActivity) {
        val entries = mutableMapOf<String, String>()
        for (file in a.assets.list("i18n").orEmpty().filter { it.startsWith("en-") && it.endsWith(".tsv") }.sorted()) {
            a.assets.open("i18n/$file").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val pair = line.removePrefix("\uFEFF").split('\t', limit = 2)
                    if (pair.size == 2 && pair[0].isNotBlank() && pair[1].isNotBlank())
                        entries[pair[0].replace("\\n","\n")] = pair[1].replace("\\n","\n")
                }
            }
        }
        catalog = EnglishCatalog(entries)
        update(a, a.prefs.getString("uiLanguage", "system") ?: "system")
    }

    fun update(a: MainActivity, language: String) {
        selection = language.takeIf { it == "en" || it == "zh" } ?: "system"
        english = when (selection) {
            "en" -> true
            "zh" -> false
            else -> a.resources.configuration.locales[0].language != "zh"
        }
    }

    fun text(source: String): String = if (english) catalog.render(source) else source
}

internal fun tr(source: String): String = UiLanguage.text(source)

@Composable
internal fun InstrumentText(text: String, modifier: Modifier = Modifier, style: TextStyle = TextStyle.Default,
                            maxLines: Int = Int.MAX_VALUE, softWrap: Boolean = true, autoSize: TextAutoSize? = null) {
    BasicText(tr(text), modifier, style = style, maxLines = maxLines, softWrap = softWrap, autoSize = autoSize)
}

internal class MeterDialog(context: Context) : AlertDialog.Builder(context) {
    override fun setTitle(title: CharSequence?) = super.setTitle(title?.let { tr(it.toString()) })
    override fun setMessage(message: CharSequence?) = super.setMessage(message?.let { tr(it.toString()) })
    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?) =
        super.setItems(items?.map { tr(it.toString()) }?.toTypedArray(), listener)
    override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?) =
        super.setPositiveButton(text?.let { tr(it.toString()) }, listener)
    override fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?) =
        super.setNegativeButton(text?.let { tr(it.toString()) }, listener)
    override fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?) =
        super.setNeutralButton(text?.let { tr(it.toString()) }, listener)
}
