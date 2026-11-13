package dev.statusbar.networkicons

import java.util.Locale

/** Xiaomi passes the number and unit separately to NetworkSpeedView. */
internal fun networkSpeedKb(number: String?, unit: String?): Double? {
    val normalized = number?.trim()?.map { char ->
        val digit = char.digitToIntOrNull()
        when {
            digit != null -> '0' + digit
            char == ',' || char == '٫' -> '.'
            else -> char
        }
    }?.joinToString("")
    val speed = normalized?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val factor = when (unit?.filterNot(Char::isWhitespace)?.uppercase(Locale.ROOT)) {
        "B/S", "B/秒" -> 1.0 / 1024
        "K/S", "KB/S", "KB/秒", "KIB/S" -> 1.0
        "M/S", "MB/S", "MB/秒", "MIB/S" -> 1024.0
        "G/S", "GB/S", "GB/秒", "GIB/S" -> 1024.0 * 1024
        else -> return null
    }
    return speed * factor
}
