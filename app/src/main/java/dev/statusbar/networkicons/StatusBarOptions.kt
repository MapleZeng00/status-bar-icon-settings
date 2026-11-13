package dev.statusbar.networkicons

import android.content.SharedPreferences

internal data class StatusBarOptions(
    val showWeather: Boolean = false,
    val speedColors: Boolean = false,
    val speedSmoothing: Boolean = false,
    val batteryColors: Boolean = false,
    val weekday: Boolean = false,
    val dimSeconds: Boolean = false,
    val tabularClock: Boolean = false,
    val clockGestures: Boolean = false,
    val iconOrder: String = "",
    val iconSpacing: Int? = null,
    val speedByClock: Boolean = false,
    val notificationApps: String? = null,
    val readingApps: String = "",
    val gamingApps: String = "",
) {
    fun save(editor: SharedPreferences.Editor) {
        editor.putBoolean("show_weather", showWeather)
            .putBoolean("speed_colors", speedColors)
            .putBoolean("speed_smoothing", speedSmoothing)
            .putBoolean("battery_colors", batteryColors)
            .putBoolean("clock_weekday", weekday)
            .putBoolean("clock_dim_seconds", dimSeconds)
            .putBoolean("clock_tabular", tabularClock)
            .putBoolean("clock_gestures", clockGestures)
            .putString("icon_order", iconOrder)
            .putInt("icon_spacing", iconSpacing ?: -1)
            .putBoolean("speed_by_clock", speedByClock)
            .putString("notification_apps", notificationApps)
            .putString("reading_apps", readingApps)
            .putString("gaming_apps", gamingApps)
    }

    companion object {
        fun read(values: Map<String, *>): StatusBarOptions = StatusBarOptions(
            showWeather = values["show_weather"] == true,
            speedColors = values["speed_colors"] == true,
            speedSmoothing = values["speed_smoothing"] == true,
            batteryColors = values["battery_colors"] == true,
            weekday = values["clock_weekday"] == true,
            dimSeconds = values["clock_dim_seconds"] == true,
            tabularClock = values["clock_tabular"] == true,
            clockGestures = values["clock_gestures"] == true,
            iconOrder = values["icon_order"] as? String ?: "",
            iconSpacing = (values["icon_spacing"] as? Int)?.takeIf { it in 0..12 },
            speedByClock = values["speed_by_clock"] == true,
            notificationApps = values["notification_apps"] as? String,
            readingApps = values["reading_apps"] as? String ?: "",
            gamingApps = values["gaming_apps"] as? String ?: "",
        )
    }
}

internal fun packageNames(value: String): Set<String> =
    value.split(Regex("[,，;；\\s]+")).filter(String::isNotBlank).toSet()

internal val sortableSlots = listOf("network_speed", "wifi", "mobile", "bluetooth", "battery")
