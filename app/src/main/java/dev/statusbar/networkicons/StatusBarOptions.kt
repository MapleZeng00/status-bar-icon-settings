package dev.statusbar.networkicons

import android.content.SharedPreferences

internal enum class WeatherLanguage { SYSTEM, CHINESE, ENGLISH }

internal const val DEFAULT_FLASH_SECONDS = 3
internal val FLASH_SECONDS = 1..15

internal data class StatusBarOptions(
    val showWeather: Boolean = false,
    val showWeatherCondition: Boolean = false,
    val weatherLanguage: WeatherLanguage = WeatherLanguage.SYSTEM,
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
    val notificationFlash: Boolean = false,
    val notificationFlashSeconds: Int = DEFAULT_FLASH_SECONDS,
    val notificationFlashStart: Int = 0xFF42A5F5.toInt(),
    val notificationFlashEnd: Int = 0xFFAB47BC.toInt(),
    val readingApps: String = "",
    val gamingApps: String = "",
    val rainNotice: Boolean = false,
    val speedGraph: Boolean = false,
    val headphoneTrack: Boolean = false,
    val timerLine: Boolean = false,
    val hourlyStar: Boolean = false,
    val chargeParticles: Boolean = false,
    val secondClock: Boolean = false,
    val privacyLabels: Boolean = false,
    val secondClockZone: String = "Europe/London",
    val secondClockApps: String = "",
    val batteryEstimate: Boolean = false,
    val calendarCountdown: Boolean = false,
    val calendarTitle: Boolean = false,
    val rainDrops: Boolean = false,
    val chargeFinale: Boolean = false,
    val unreadDot: Boolean = false,
) {
    fun save(editor: SharedPreferences.Editor) {
        editor.putBoolean("show_weather", showWeather)
            .putBoolean("show_weather_condition", showWeatherCondition)
            .putString("weather_language", weatherLanguage.name)
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
            .putBoolean("notification_flash", notificationFlash)
            .putInt("notification_flash_seconds", notificationFlashSeconds)
            .putInt("notification_flash_start", notificationFlashStart)
            .putInt("notification_flash_end", notificationFlashEnd)
            .putString("reading_apps", readingApps)
            .putString("gaming_apps", gamingApps)
            .putBoolean("rain_notice", rainNotice)
            .putBoolean("speed_graph", speedGraph)
            .putBoolean("headphone_track", headphoneTrack)
            .putBoolean("timer_line", timerLine)
            .putBoolean("hourly_star", hourlyStar)
            .putBoolean("charge_particles", chargeParticles)
            .putBoolean("second_clock", secondClock)
            .putBoolean("privacy_labels", privacyLabels)
            .putString("second_clock_zone", secondClockZone)
            .putString("second_clock_apps", secondClockApps)
            .putBoolean("battery_estimate", batteryEstimate)
            .putBoolean("calendar_countdown", calendarCountdown)
            .putBoolean("calendar_title", calendarTitle)
            .putBoolean("rain_drops", rainDrops)
            .putBoolean("charge_finale", chargeFinale)
            .putBoolean("unread_dot", unreadDot)
    }

    companion object {
        fun read(values: Map<String, *>): StatusBarOptions = StatusBarOptions(
            showWeather = values["show_weather"] == true,
            showWeatherCondition = values["show_weather_condition"] == true,
            weatherLanguage = WeatherLanguage.entries.firstOrNull { it.name == values["weather_language"] }
                ?: WeatherLanguage.SYSTEM,
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
            notificationFlash = values["notification_flash"] == true,
            notificationFlashSeconds = (values["notification_flash_seconds"] as? Int)
                ?.takeIf { it in FLASH_SECONDS } ?: DEFAULT_FLASH_SECONDS,
            notificationFlashStart = values["notification_flash_start"] as? Int ?: 0xFF42A5F5.toInt(),
            notificationFlashEnd = values["notification_flash_end"] as? Int ?: 0xFFAB47BC.toInt(),
            readingApps = values["reading_apps"] as? String ?: "",
            gamingApps = values["gaming_apps"] as? String ?: "",
            rainNotice = values["rain_notice"] == true,
            speedGraph = values["speed_graph"] == true,
            headphoneTrack = values["headphone_track"] == true,
            timerLine = values["timer_line"] == true,
            hourlyStar = values["hourly_star"] == true,
            chargeParticles = values["charge_particles"] == true,
            secondClock = values["second_clock"] == true,
            privacyLabels = values["privacy_labels"] == true,
            secondClockZone = values["second_clock_zone"] as? String ?: "Europe/London",
            secondClockApps = values["second_clock_apps"] as? String ?: "",
            batteryEstimate = values["battery_estimate"] == true,
            calendarCountdown = values["calendar_countdown"] == true,
            calendarTitle = values["calendar_title"] == true,
            rainDrops = values["rain_drops"] == true,
            chargeFinale = values["charge_finale"] == true,
            unreadDot = values["unread_dot"] == true,
        )
    }
}

internal fun packageNames(value: String): Set<String> =
    value.split(Regex("[,，;；\\s]+")).filter(String::isNotBlank).toSet()

internal val sortableSlots = listOf("network_speed", "wifi", "mobile", "bluetooth", "battery")
