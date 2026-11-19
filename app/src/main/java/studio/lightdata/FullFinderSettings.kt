package studio.lightdata

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

internal enum class GlassPart(val label: String) {
    FINDER("取景框"), HISTOGRAM("直方图"), METER("测光表")
}

// Each coordinate is a fraction of the available travel after subtracting panel size.
internal data class GlassAnchor(val x: Float, val y: Float)
internal data class GlassLayout(val anchors: List<GlassAnchor>, val finderScale: Float = 1f) {
    fun move(part: GlassPart, x: Float, y: Float) = copy(anchors = anchors.mapIndexed { index, previous ->
        if (index == part.ordinal) GlassAnchor(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) else previous
    })

    fun encode() = JSONArray().apply {
        put(finderScale.toDouble())
        for (position in anchors) { put(position.x.toDouble()); put(position.y.toDouble()) }
    }

    companion object {
        fun decode(data: JSONArray): GlassLayout {
            require(data.length() == 7)
            fun number(i: Int) = data.getDouble(i).also { require(it.isFinite()) }.toFloat()
            return GlassLayout(List(3) { GlassAnchor(number(1 + it*2).coerceIn(0f,1f),
                number(2 + it*2).coerceIn(0f,1f)) }, number(0).coerceIn(.55f,1f))
        }
    }
}

internal class FullFinderSettings(private val prefs: SharedPreferences) {
    var enabled by mutableStateOf(prefs.getBoolean("fullFinder.enabled", false))
        private set

    fun toggle() {
        enabled = !enabled
        prefs.edit().putBoolean("fullFinder.enabled", enabled).apply()
    }

    private fun key(landscape: Boolean) = if (landscape) "fullFinder.landscape" else "fullFinder.portrait"
    fun current(landscape: Boolean) = prefs.getString(key(landscape), null)?.let {
        runCatching { GlassLayout.decode(JSONArray(it)) }.getOrNull()
    }
    fun use(landscape: Boolean, value: GlassLayout?) {
        prefs.edit().putString(key(landscape), value?.encode()?.toString()).apply()
    }
    private fun presets(landscape: Boolean) =
        runCatching { JSONObject(prefs.getString(key(landscape) + ".presets", "{}") ?: "{}") }.getOrElse { JSONObject() }
    fun names(landscape: Boolean) = presets(landscape).keys().asSequence().sorted().toList()
    fun load(landscape: Boolean, name: String) = runCatching {
        GlassLayout.decode(presets(landscape).getJSONArray(name))
    }.getOrNull()
    fun save(landscape: Boolean, name: String, layout: GlassLayout) {
        prefs.edit().putString(key(landscape) + ".presets", presets(landscape).put(name, layout.encode()).toString()).apply()
    }
}

/** Fit the frame without cropping. Preset coordinates refer to each component's available travel. */
internal fun glassBounds(w: Float, h: Float, density: Float, aspect: Float, landscape: Boolean,
                         distance: Boolean, saved: GlassLayout?): Pair<GlassLayout, List<Rect>> {
    val histW = if (landscape) min(w*.41f,288*density) else min(w*(if(distance) .39f else .46f),235*density)
    val meterW = if (landscape) histW else min(w-histW,310*density)
    val meterH = if (landscape) min(h*.54f,260*density) else min(h*.36f,245*density)
    val histH = if (landscape) min(h-meterH,210*density) else meterH
    // In portrait the two instruments share the bottom edge; in landscape they share the right edge.
    fun fraction(offset: Float, travel: Float) = if (travel > 0) (offset / travel).coerceIn(0f,1f) else 0f
    val defaults = GlassLayout(listOf(if(landscape) GlassAnchor(0f,0f) else GlassAnchor(1f,0f),
        if(landscape) GlassAnchor(1f,fraction(h-meterH-histH,h-histH)) else GlassAnchor(0f,1f),
        if(landscape) GlassAnchor(1f,1f) else GlassAnchor(fraction(histW,w-meterW),1f)))
    val layout = saved ?: defaults
    val finderW = min(w,h*aspect) * layout.finderScale
    fun rect(part: GlassPart, width:Float, height:Float):Rect {
        val xy = layout.anchors[part.ordinal]
        val x = (w-width)*xy.x
        val y = (h-height)*xy.y
        return Rect(x,y,x+width,y+height)
    }
    val bounds = listOf(rect(GlassPart.FINDER,finderW,finderW/aspect),
        rect(GlassPart.HISTOGRAM,histW,histH),rect(GlassPart.METER,meterW,meterH))
    return layout to bounds
}
