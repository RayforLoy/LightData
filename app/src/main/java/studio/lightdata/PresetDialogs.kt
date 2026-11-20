package studio.lightdata

import android.app.AlertDialog
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

internal fun MainActivity.chooseFilm() {
    val brands = filmRecords.map { it.brand }.distinct().sorted()
    choose("曝光－密度曲线", brands + "自定义预设") { index ->
        if (index < brands.size) {
            val records = filmRecords.filter { it.brand == brands[index] }.sortedBy { it.film.name }
            choose(brands[index], records.map { "${it.film.name}  ·  ${if (it.experimentalEi) "EI" else "ISO"} ${it.iso}" }) { selectFilm(records[it]) }
        } else {
            val presets = customPresets.sortedBy { it.name }
            val names = presets.map { "${it.name}  ·  ISO ${isoLabel(it.iso)}" }
            choose("自定义预设", names + "新建预设…") { i ->
                if (i == presets.size) editCustomPreset() else selectPreset(presets[i])
            }
        }
    }
}

internal fun MainActivity.editCustomPreset(original: CustomPreset? = null) {
    val inset = (20 * resources.displayMetrics.density).toInt()
    val form = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(inset, inset / 2, inset, 0)
    }
    fun label(title: String) {
        form.addView(TextView(this).apply { text = tr(title) })
    }
    fun field(title: String, value: String, numeric: Boolean = true): EditText {
        label(title)
        return EditText(this).apply {
            setSingleLine()
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_TEXT
            setText(value)
            form.addView(this)
        }
    }
    val name = field("名称", original?.name.orEmpty(), false)
    label("类型")
    val type = Spinner(this).apply {
        adapter = ArrayAdapter(this@editCustomPreset, android.R.layout.simple_spinner_dropdown_item,
            listOf(tr("胶片"), tr("数码")))
        setSelection(if (original?.digital == true) 1 else 0)
        form.addView(this)
    }
    val speed = field("标称 ISO", isoLabel(original?.iso ?: manual.iso))
    val span = field("总动态范围（EV）", n(original?.total ?: 12.0))
    val above = field("中灰以上（EV）", n(original?.above ?: 4.0))
    val dialog = MeterDialog(this)
        .setTitle(if (original == null) "新建自定义预设" else "编辑自定义预设")
        .setView(ScrollView(this).apply { addView(form) })
        .setPositiveButton("保存并选用", null).setNegativeButton("取消", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val preset = CustomPreset.create(name.text.toString().trim(), type.selectedItemPosition == 1,
                    span.text.toString().toDoubleOrNull() ?: Double.NaN,
                    above.text.toString().toDoubleOrNull() ?: Double.NaN,
                    speed.text.toString().toDoubleOrNull() ?: Double.NaN)
                savePreset(if (original == null) preset else preset.copy(id = original.id))
                dialog.dismiss()
            } catch (error: IllegalArgumentException) {
                notifyUser(error.localizedMessage ?: "请检查预设参数")
            }
        }
    }
    dialog.show()
}
