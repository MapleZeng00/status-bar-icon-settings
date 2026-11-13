package dev.statusbar.networkicons

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
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
                    show(reading)
                    frame.postDelayed(refresh, 5 * 60_000L)
                }
            }
        }
    }

    private fun show(reading: Reading?) {
        label.text = reading?.temperature.orEmpty()
        label.contentDescription = reading?.let { listOfNotNull(it.description, it.temperature).joinToString(" ") }
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

    private data class Reading(val temperature: String, val description: String?)

    private companion object {
        val reader = Executors.newSingleThreadExecutor()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val uri = Uri.parse("content://weather/weather")
        val validTemperature = Regex("[+−-]?\\d{1,3}(?:[.,]\\d)?\\s*(?:℃|℉|°[CF]?)")

        fun read(context: Context): Reading? = try {
            context.contentResolver.query(uri, arrayOf("temperature", "description"), null, null, null)
                ?.use { rows ->
                    if (!rows.moveToFirst()) return@use null
                    val index = rows.getColumnIndex("temperature")
                    val temperature = if (index >= 0) rows.getString(index)?.trim() else null
                    if (temperature == null || !validTemperature.matches(temperature)) return@use null
                    val description = rows.getColumnIndex("description")
                    Reading(temperature, if (description >= 0) rows.getString(description) else null)
                }
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Xiaomi weather data unavailable (${error.javaClass.simpleName})")
            null
        }
    }
}
