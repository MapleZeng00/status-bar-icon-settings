package dev.statusbar.networkicons

import android.content.SharedPreferences

internal const val LOG_TAG = "NetworkStatusIcons"
internal const val PREF_GROUP = "network_icons"

internal const val DEFAULT_LINE_COLOR = 0xFF00C896.toInt()

internal enum class ChargingAnimation {
    OFF, GRADIENT, WAVE, COMET, DOUBLE_SWEEP, BREATH;

    companion object {
        fun fromPreference(value: Any?): ChargingAnimation =
            if (value == "ECG") WAVE else entries.firstOrNull { it.name == value } ?: OFF
    }
}

internal data class IconSettings(
    val hideMobile: Boolean,
    val hideWifi: Boolean,
    val hideNoSim: Boolean,
    val hideBattery: Boolean,
    val networkSpeedThreshold: Int?,
    val batteryLine: Boolean,
    val batteryLineColor: Int,
    val chargingAnimation: ChargingAnimation,
    val clockSeconds: Boolean,
    val clockColor: Int?,
    val debug: Boolean,
) {
    fun save(prefs: SharedPreferences): Boolean = prefs.edit()
        .putBoolean("hide_mobile", hideMobile)
        .putBoolean("hide_wifi", hideWifi)
        .putBoolean("hide_no_sim", hideNoSim)
        .putBoolean("hide_battery", hideBattery)
        .apply {
            if (networkSpeedThreshold == null) remove("network_speed_threshold")
            else putInt("network_speed_threshold", networkSpeedThreshold)
        }
        .putBoolean("battery_line", batteryLine)
        .putInt("battery_line_color", batteryLineColor)
        .putString("charging_animation", chargingAnimation.name)
        .putBoolean("clock_seconds", clockSeconds)
        .apply {
            if (clockColor == null) remove("clock_color") else putInt("clock_color", clockColor)
        }
        .putBoolean("debug", debug)
        .commit()

    companion object {
        fun read(prefs: SharedPreferences): IconSettings {
            val values = prefs.all
            return IconSettings(
                values["hide_mobile"] == true,
                values["hide_wifi"] == true,
                values["hide_no_sim"] == true,
                values["hide_battery"] == true,
                (values["network_speed_threshold"] as? Int)?.takeIf { it >= 0 },
                values["battery_line"] == true,
                (values["battery_line_color"] as? Int) ?: DEFAULT_LINE_COLOR,
                ChargingAnimation.fromPreference(values["charging_animation"]),
                values["clock_seconds"] == true,
                values["clock_color"] as? Int,
                values["debug"] == true,
            )
        }
    }
}
