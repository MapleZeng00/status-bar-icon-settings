package dev.statusbar.networkicons

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal fun showStatusBarOptions(context: Context, initial: StatusBarOptions, onSave: (StatusBarOptions) -> Unit) {
    var draft = initial
    val density = context.resources.displayMetrics.density
    val padding = (20 * density).toInt()
    val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, 0, padding, padding)
    }
    fun title(label: String) {
        body.addView(TextView(context).apply {
            text = label
            textSize = 17f
            setPadding(0, padding, 0, padding / 3)
            isAccessibilityHeading = true
        })
    }
    fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
        body.addView(CheckBox(context).apply {
            text = label
            isChecked = checked
            setOnCheckedChangeListener { _, enabled -> change(enabled) }
        })
    }
    fun field(label: String, value: String): EditText {
        body.addView(TextView(context).apply { text = label; setPadding(0, padding / 2, 0, 0) })
        return EditText(context).apply {
            setSingleLine()
            setText(value)
            body.addView(this)
        }
    }
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = context.packageManager.queryIntentActivities(launcher, 0)
        .associate { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
    fun appField(label: String, value: String): EditText {
        val input = field(label, value)
        body.addView(android.widget.Button(context).apply {
            text = "选择应用"
            setOnClickListener {
                val selected = packageNames(input.text.toString()).toMutableSet()
                val entries = (apps + selected.filterNot(apps::containsKey).associateWith { it })
                    .entries.sortedBy { it.value.lowercase() }
                val checked = entries.map { it.key in selected }.toBooleanArray()
                AlertDialog.Builder(context).setTitle(label)
                    .setMultiChoiceItems(entries.map { it.value }.toTypedArray(), checked) { _, i, enabled ->
                        if (enabled) selected.add(entries[i].key) else selected.remove(entries[i].key)
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ -> input.setText(selected.joinToString(", ")) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        })
        return input
    }

    title("网速与电量")
    toggle("网速分档变色（50 / 1024 KB/s）", initial.speedColors) { draft = draft.copy(speedColors = it) }
    toggle("网速防抖（连续低速至少 3 秒才隐藏）", initial.speedSmoothing) { draft = draft.copy(speedSmoothing = it) }
    toggle("电量线按电量变色（绿色／橙色／红色）", initial.batteryColors) { draft = draft.copy(batteryColors = it) }
    title("时间")
    toggle("时间前显示星期", initial.weekday) { draft = draft.copy(weekday = it) }
    toggle("淡化秒数", initial.dimSeconds) { draft = draft.copy(dimSeconds = it) }
    toggle("等宽数字", initial.tabularClock) { draft = draft.copy(tabularClock = it) }
    toggle("点按时间切换秒数，长按打开闹钟", initial.clockGestures) { draft = draft.copy(clockGestures = it) }
    title("图标布局")
    toggle("网速移到时间旁边", initial.speedByClock) { draft = draft.copy(speedByClock = it) }
    val order = field("图标顺序（留空为系统顺序）", initial.iconOrder)
    order.hint = "网速, Wi-Fi, 信号, 蓝牙, 电池"
    body.addView(TextView(context).apply {
        text = "电池位于独立区域，可放在整组图标的左端或右端。"
    })
    val spacing = field("图标间距（0–12 dp，留空为系统间距）", initial.iconSpacing?.toString().orEmpty())
    spacing.inputType = android.text.InputType.TYPE_CLASS_NUMBER
    title("通知与应用样式")
    toggle("启用顶部通知图标白名单", initial.notificationApps != null) {
        draft = draft.copy(notificationApps = if (it) "" else null)
    }
    val allowed = appField("允许的通知应用", initial.notificationApps.orEmpty())
    val reading = appField("阅读样式应用（仅保留时间和电量线）", initial.readingApps)
    val gaming = appField("游戏样式应用（仅保留网速）", initial.gamingApps)

    val dialog = AlertDialog.Builder(context).setTitle("状态栏进阶设置")
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, null).show()
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val spacingText = spacing.text.toString().trim()
        val dp = spacingText.toIntOrNull()
        if (spacingText.isNotEmpty() && (dp == null || dp !in 0..12)) {
            spacing.error = "请输入 0–12"
            return@setOnClickListener
        }
        val aliases = mapOf("网速" to "network_speed", "Wi-Fi" to "wifi", "信号" to "mobile", "蓝牙" to "bluetooth", "电池" to "battery")
        val slots = order.text.toString().split(',', '，').map(String::trim).filter(String::isNotEmpty)
            .map { aliases[it] ?: it.lowercase() }
        if ("battery" in slots.drop(1).dropLast(1)) {
            order.error = "请将电池放在顺序的开头或末尾"
            return@setOnClickListener
        }
        if (slots.any { it !in sortableSlots } || slots.size != slots.distinct().size) {
            order.error = "请用逗号分隔网速、Wi-Fi、信号、蓝牙、电池"
            return@setOnClickListener
        }
        val packages = listOf(allowed, reading, gaming)
        val invalid = packages.firstOrNull { field ->
            packageNames(field.text.toString()).any { !it.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) }
        }
        if (invalid != null) {
            invalid.error = "请选择应用或输入包名"
            return@setOnClickListener
        }
        onSave(draft.copy(
            iconOrder = slots.joinToString(","),
            iconSpacing = dp,
            notificationApps = draft.notificationApps?.let { packageNames(allowed.text.toString()).joinToString(",") },
            readingApps = packageNames(reading.text.toString()).joinToString(","),
            gamingApps = packageNames(gaming.text.toString()).joinToString(","),
        ))
        dialog.dismiss()
    }
}
