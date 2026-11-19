package studio.lightdata

import android.app.AlertDialog
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject

private fun Format.menuLabel() = "$name   ${n(w)} × ${n(h)} mm"

internal fun MainActivity.selectFormatCategory() {
    val groups = formatGroups.keys.toList() + "自定义"
    choose("目标画幅", groups) { i ->
        val group = groups[i]
        val entries = formatGroups[group]
        if (entries == null) customFormatMenu()
        else choose(group, entries.map { it.menuLabel() }) { applyFormat(entries[it]) }
    }
}

private fun MainActivity.customFormatMenu() {
    val data = runCatching { JSONArray(prefs.getString("formatPresets", "[]")) }.getOrElse { JSONArray() }
    val presets = (0 until data.length()).mapNotNull { i ->
        val row = data.optJSONObject(i) ?: return@mapNotNull null
        Format(row.optString("name"), row.optDouble("width"), row.optDouble("height"))
            .takeIf { it.name.isNotBlank() && it.w in 0.1..1500.0 && it.h in 0.1..1500.0 }
    }
    choose("自定义画幅", presets.map { it.menuLabel() } + listOf("输入画幅尺寸…", "新建命名预设…")) { i ->
        if (i < presets.size) applyFormat(presets[i])
        else choose("自定义画幅单位", listOf("毫米", "英寸")) { unit ->
            val factor = mm(1.0, unit == 1)
            val range = .1 / factor..1500.0 / factor
            val label = if (unit == 1) "英寸" else "mm"
            input("画幅宽度（$label）", format.w / factor, range) { width ->
                input("画幅高度（$label）", format.h / factor, range) { height ->
                    val size = Format("自定义", width * factor, height * factor)
                    if (i == presets.size) applyFormat(size)
                    else {
                        val name = EditText(this).apply { setSingleLine(); hint = "例如 612 片夹" }
                        val dialog = AlertDialog.Builder(this).setTitle("保存画幅预设").setView(name)
                            .setNegativeButton("取消", null).setPositiveButton("保存并使用", null).create()
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val title = name.text.toString().trim()
                                when {
                                    title.isEmpty() -> name.error = "请输入预设名称"
                                    title == "自定义" || (formats + presets).any { it.name == title } ->
                                        name.error = "这个名称已被使用"
                                    else -> {
                                        data.put(JSONObject().put("name", title).put("width", size.w).put("height", size.h))
                                        prefs.edit().putString("formatPresets", data.toString()).apply()
                                        applyFormat(size.copy(name = title))
                                        dialog.dismiss()
                                    }
                                }
                            }
                        }
                        dialog.show()
                    }
                }
            }
        }
    }
}
