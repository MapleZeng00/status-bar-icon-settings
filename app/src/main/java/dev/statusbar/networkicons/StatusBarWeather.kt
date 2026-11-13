package dev.statusbar.networkicons

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.text.TextUtils
import java.util.Locale
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

internal class StatusBarWeather(
    private val frame: FrameLayout,
    private val contents: View,
) : View.OnAttachStateChangeListener {
    private val label = TextView(frame.context).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = 12f
        setSingleLine()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = View.GONE
    }
    private var active = false
    private var generation = 0
    private var reserved = 0
    private var showCondition = false
    private var language = WeatherLanguage.SYSTEM
    private var latestReading: Reading? = null
    private val refresh = Runnable { readWeather() }

    fun install() {
        val placement = FrameLayout.LayoutParams(-2, -1, Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        placement.rightMargin = contents.paddingRight
        placement.topMargin = contents.paddingTop
        placement.bottomMargin = contents.paddingBottom
        frame.addView(label, placement)
        frame.addOnAttachStateChangeListener(this)
        if (frame.isAttachedToWindow) onViewAttachedToWindow(frame)
    }

    fun uninstall() {
        frame.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(frame)
        show(null)
        frame.removeView(label)
    }

    fun configure(showCondition: Boolean, language: WeatherLanguage) {
        if (this.showCondition == showCondition && this.language == language) return
        this.showCondition = showCondition
        this.language = language
        show(latestReading)
    }

    fun tint(colors: ColorStateList) {
        label.setTextColor(colors)
    }

    override fun onViewAttachedToWindow(view: View) {
        if (active) return
        active = true
        readWeather()
    }

    override fun onViewDetachedFromWindow(view: View) {
        active = false
        generation++
        frame.removeCallbacks(refresh)
    }

    private fun readWeather() {
        val request = ++generation
        val context = frame.context
        reader.execute {
            val reading = read(context)
            main.post {
                if (active && generation == request) {
                    latestReading = reading
                    show(reading)
                    frame.postDelayed(refresh, 5 * 60_000L)
                }
            }
        }
    }

    private fun show(reading: Reading?) {
        val temperature = reading?.temperature.orEmpty()
        val selectedLanguage = when (language) {
            WeatherLanguage.SYSTEM -> label.resources.configuration.locales[0]?.language.orEmpty()
            WeatherLanguage.CHINESE -> "zh"
            WeatherLanguage.ENGLISH -> "en"
        }
        val description = reading?.takeIf { showCondition }?.condition(selectedLanguage)
        val display = if (description.isNullOrBlank()) temperature else {
            val maxWidth = label.resources.displayMetrics.widthPixels * 0.30f
            val space = maxWidth - label.paint.measureText(" $temperature")
            val abbreviated = TextUtils.ellipsize(description, label.paint, space.coerceAtLeast(0f), TextUtils.TruncateAt.END)
            if (abbreviated.isEmpty()) temperature else "$abbreviated $temperature"
        }
        label.text = display
        label.contentDescription = reading?.let { listOfNotNull(description, temperature).joinToString(" ") }
        label.visibility = if (reading == null) View.GONE else View.VISIBLE
        val next = if (reading == null) 0 else {
            label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            label.measuredWidth + (4 * label.resources.displayMetrics.density).roundToInt()
        }
        if (reserved == next) return
        val placement = contents.layoutParams as? FrameLayout.LayoutParams ?: return
        placement.rightMargin = (placement.rightMargin - reserved + next).coerceAtLeast(0)
        reserved = next
        contents.layoutParams = placement
    }

    private data class Reading(
        val temperature: String,
        val description: String?,
        val locale: String?,
        val code: Int?,
    ) {
        fun condition(language: String): String? {
            val translated = when (language) {
                "zh" -> code?.let(chineseConditions::getOrNull)
                "en" -> code?.let(englishConditions::getOrNull)
                else -> null
            }
            if (translated != null) return translated
            val sourceLanguage = locale?.let { Locale.forLanguageTag(it.replace('_', '-')).language }
            return description?.takeIf { code != 99 && (sourceLanguage == language || language.isEmpty()) }
        }
    }

    private companion object {
        val reader = Executors.newSingleThreadExecutor()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val uri = Uri.parse("content://weather/weather")
        // The indices are the current-weather codes documented by Xiaomi's weather provider.
        val chineseConditions = listOf(
            "晴", "多云", "阴", "雾", "特大暴雨", "大暴雨", "暴雨", "雷阵雨", "阵雨",
            "大雨", "中雨", "小雨", "雨夹雪", "暴雪", "阵雪", "大雪", "中雪", "小雪",
            "强沙尘暴", "沙尘暴", "沙尘", "扬沙", "冰雹", "浮尘", "霾", "冻雨",
        )
        val englishConditions = listOf(
            "Clear", "Cloudy", "Overcast", "Fog", "Extreme rain", "Torrential rain", "Rainstorm",
            "Thunderstorms", "Showers", "Heavy rain", "Moderate rain", "Light rain", "Sleet",
            "Blizzard", "Snow showers", "Heavy snow", "Moderate snow", "Light snow",
            "Severe sandstorm", "Sandstorm", "Sand", "Blowing sand", "Hail", "Dust", "Haze", "Freezing rain",
        )
        val validTemperature = Regex("[+−-]?\\d{1,3}(?:[.,]\\d)?\\s*(?:℃|℉|°[CF]?)")

        fun read(context: Context): Reading? = try {
            context.contentResolver.query(uri, arrayOf("temperature", "description", "locale", "weather_type"), null, null, null)
                ?.use { rows ->
                    if (!rows.moveToFirst()) return@use null
                    val index = rows.getColumnIndex("temperature")
                    val temperature = if (index >= 0) rows.getString(index)?.trim() else null
                    if (temperature == null || !validTemperature.matches(temperature)) return@use null
                    val description = rows.getColumnIndex("description")
                    val locale = rows.getColumnIndex("locale")
                    val code = rows.getColumnIndex("weather_type")
                    Reading(
                        temperature,
                        if (description >= 0) rows.getString(description)?.trim() else null,
                        if (locale >= 0) rows.getString(locale) else null,
                        if (code >= 0) rows.getString(code)?.toIntOrNull() else null,
                    )
                }
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Xiaomi weather data unavailable (${error.javaClass.simpleName})")
            null
        }
    }
}
