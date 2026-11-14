package dev.statusbar.networkicons

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal fun showStatusBarOptions(
    context: Context, initial: StatusBarOptions, batteryColor: Int, chargingAnimation: ChargingAnimation,
    onSave: (StatusBarOptions) -> Unit,
) {
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

    title("天气")
    toggle("显示天气（状态栏最右侧）", initial.showWeather) { draft = draft.copy(showWeather = it) }
    toggle("降雨提醒（需开启天气）", initial.rainNotice) { draft = draft.copy(rainNotice = it) }
    toggle("降雨提醒出现时播放小雨滴", initial.rainDrops) { draft = draft.copy(rainDrops = it) }
    toggle(context.getString(R.string.weather_condition), initial.showWeatherCondition) {
        draft = draft.copy(showWeatherCondition = it)
    }
    val weatherLanguages = context.resources.getStringArray(R.array.weather_languages)
    body.addView(Button(context).apply {
        fun updateLabel() {
            text = context.getString(R.string.weather_language_value, weatherLanguages[draft.weatherLanguage.ordinal])
        }
        updateLabel()
        setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(R.string.weather_language)
                .setSingleChoiceItems(weatherLanguages, draft.weatherLanguage.ordinal) { dialog, index ->
                    draft = draft.copy(weatherLanguage = WeatherLanguage.entries[index])
                    updateLabel()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    })
    title("网速与电量")
    toggle("网速分档变色（50 / 1024 KB/s）", initial.speedColors) { draft = draft.copy(speedColors = it) }
    toggle("网速走势图（最近 10 秒）", initial.speedGraph) { draft = draft.copy(speedGraph = it) }
    toggle("网速防抖（连续低速至少 3 秒才隐藏）", initial.speedSmoothing) { draft = draft.copy(speedSmoothing = it) }
    toggle("电量线按电量变色（绿色／橙色／红色）", initial.batteryColors) { draft = draft.copy(batteryColors = it) }
    toggle("系统计时器进度线", initial.timerLine) { draft = draft.copy(timerLine = it) }
    toggle("充电达到 80% 和 100% 时显示粒子", initial.chargeParticles) { draft = draft.copy(chargeParticles = it) }
    toggle("点击状态栏空白处查看续航或充满时间", initial.batteryEstimate) { draft = draft.copy(batteryEstimate = it) }
    toggle("充满电时播放星星收尾动画", initial.chargeFinale) { draft = draft.copy(chargeFinale = it) }
    title("时间")
    toggle("下一场日程倒计时（提前 30 分钟）", initial.calendarCountdown) { draft = draft.copy(calendarCountdown = it) }
    toggle("同时显示日程标题（默认隐藏）", initial.calendarTitle) { draft = draft.copy(calendarTitle = it) }
    toggle("时间前显示星期", initial.weekday) { draft = draft.copy(weekday = it) }
    toggle("淡化秒数", initial.dimSeconds) { draft = draft.copy(dimSeconds = it) }
    toggle("等宽数字", initial.tabularClock) { draft = draft.copy(tabularClock = it) }
    toggle("白天整点星光", initial.hourlyStar) { draft = draft.copy(hourlyStar = it) }
    toggle("显示第二时区", initial.secondClock) { draft = draft.copy(secondClock = it) }
    val zone = field("第二时区（时区 ID）", initial.secondClockZone)
    body.addView(Button(context).apply {
        text = "选择时区"
        setOnClickListener {
            val zones = java.time.ZoneId.getAvailableZoneIds().filter { '/' in it }.sorted()
            AlertDialog.Builder(context).setTitle("第二时区").setItems(zones.toTypedArray()) { _, index ->
                zone.setText(zones[index])
            }.show()
        }
    })
    val secondClockApps = appField("第二时区显示范围（留空为全部应用）", initial.secondClockApps)
    toggle("时钟手势：单击切换显秒，长按打开闹钟", initial.clockGestures) { draft = draft.copy(clockGestures = it) }
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
    toggle("显示未读通知小点", initial.unreadDot) { draft = draft.copy(unreadDot = it) }
    toggle("收到通知时闪烁状态栏", initial.notificationFlash) { draft = draft.copy(notificationFlash = it) }
    val flashSeconds = field("闪烁持续时间（${FLASH_SECONDS.first}–${FLASH_SECONDS.last} 秒）",
        initial.notificationFlashSeconds.toString())
    flashSeconds.inputType = android.text.InputType.TYPE_CLASS_NUMBER
    fun flashColor(titleId: Int, current: () -> Int, change: (Int) -> Unit) {
        body.addView(Button(context).apply {
            fun updateLabel() {
                text = context.getString(R.string.notification_flash_color_value, context.getString(titleId), colorHex(current()))
            }
            updateLabel()
            setOnClickListener {
                showColorPicker(context, titleId, current()) {
                    change(it)
                    updateLabel()
                }
            }
        })
    }
    flashColor(R.string.notification_flash_start, { draft.notificationFlashStart }) {
        draft = draft.copy(notificationFlashStart = it)
    }
    flashColor(R.string.notification_flash_end, { draft.notificationFlashEnd }) {
        draft = draft.copy(notificationFlashEnd = it)
    }
    body.addView(Button(context).apply {
        text = "模拟预览"
        setOnClickListener {
            val duration = flashSeconds.text.toString().trim().toIntOrNull()?.takeIf { it in FLASH_SECONDS }
                ?: draft.notificationFlashSeconds
            showStatusBarPreview(context, draft.copy(notificationFlashSeconds = duration), batteryColor, chargingAnimation)
        }
    })
    toggle("耳机播放时短暂显示新曲名", initial.headphoneTrack) { draft = draft.copy(headphoneTrack = it) }
    toggle("在系统隐私指示器旁显示摄像头／麦克风文字", initial.privacyLabels) { draft = draft.copy(privacyLabels = it) }
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
        val seconds = flashSeconds.text.toString().trim().toIntOrNull()
        if (seconds == null || seconds !in FLASH_SECONDS) {
            flashSeconds.error = "请输入 ${FLASH_SECONDS.first}–${FLASH_SECONDS.last} 秒"
            flashSeconds.requestFocus()
            return@setOnClickListener
        }
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
        val zoneId = zone.text.toString().trim().ifEmpty { "Europe/London" }
        if (runCatching { java.time.ZoneId.of(zoneId) }.isFailure) {
            zone.error = "请选择有效时区"
            return@setOnClickListener
        }
        val packages = listOf(allowed, reading, gaming, secondClockApps)
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
            notificationFlashSeconds = seconds,
            readingApps = packageNames(reading.text.toString()).joinToString(","),
            gamingApps = packageNames(gaming.text.toString()).joinToString(","),
            secondClockZone = zoneId,
            secondClockApps = packageNames(secondClockApps.text.toString()).joinToString(","),
        ))
        dialog.dismiss()
    }
}
