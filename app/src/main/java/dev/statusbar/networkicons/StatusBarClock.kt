package dev.statusbar.networkicons

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.PowerManager
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.format.DateFormat
import java.text.FieldPosition
import android.util.Log
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class StatusBarClock(
    private val view: TextView,
) : View.OnAttachStateChangeListener {
    private var options = StatusBarOptions()
    private var originalFontFeatures: String? = null
    private var tabularApplied = false
    private var showSeconds = false
    private var dimSeconds = false
    private var color: Int? = null
    private var systemColor: ColorStateList? = null
    private var format: SimpleDateFormat? = null
    private var formatLocale: Locale? = null
    private var format24Hour = false
    private var listening = false
    private val power = view.context.getSystemService(PowerManager::class.java)
    private val tick = Runnable { scheduleTick() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> view.removeCallbacks(tick)
                Intent.ACTION_SCREEN_ON -> scheduleTick()
            }
        }
    }

    fun install() {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    fun restoreSystemColor() {
        systemColor?.let(view::setTextColor)
        systemColor = null
    }

    fun configure(seconds: Boolean, customColor: Int?, next: StatusBarOptions, dim: Boolean) {
        dimSeconds = dim
        if (options != next) format = null
        options = next
        if (next.tabularClock != tabularApplied) {
            if (next.tabularClock) {
                originalFontFeatures = view.fontFeatureSettings
                view.fontFeatureSettings = listOfNotNull(originalFontFeatures?.takeIf(String::isNotBlank), "'tnum'").joinToString(", ")
            } else {
                view.fontFeatureSettings = originalFontFeatures
            }
            tabularApplied = next.tabularClock
        }
        if (color != customColor) restoreSystemColor()
        color = customColor
        if (showSeconds != seconds) {
            showSeconds = seconds
            if (!seconds) onViewDetachedFromWindow(view)
            else if (view.isAttachedToWindow) onViewAttachedToWindow(view)
        }
    }

    override fun onViewAttachedToWindow(view: View) {
        if (!showSeconds || listening) return
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            if (Build.VERSION.SDK_INT >= 33) {
                view.context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                view.context.registerReceiver(screenReceiver, filter)
            }
            listening = true
            scheduleTick()
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Unable to start clock seconds ticker", error)
        }
    }

    override fun onViewDetachedFromWindow(view: View) {
        view.removeCallbacks(tick)
        if (listening) {
            listening = false
            try {
                view.context.unregisterReceiver(screenReceiver)
            } catch (error: RuntimeException) {
                Log.w(LOG_TAG, "Unable to unregister clock receiver", error)
            }
        }
    }

    private fun scheduleTick() {
        view.removeCallbacks(tick)
        if (!view.isAttachedToWindow || power?.isInteractive == false) return
        try {
            if (view.isShown && view.windowVisibility == View.VISIBLE) refresh(System.currentTimeMillis())
            view.postDelayed(tick, 1000 - Math.floorMod(System.currentTimeMillis(), 1000L))
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Clock seconds ticker stopped", error)
        }
    }

    fun refresh(now: Long = System.currentTimeMillis()) {
        color?.let { custom ->
            if (systemColor == null) systemColor = view.textColors
            if (view.currentTextColor != custom) view.setTextColor(custom)
        }
        if (!showSeconds && !options.weekday) return
        val locale = view.resources.configuration.locales[0] ?: Locale.getDefault()
        val use24Hour = DateFormat.is24HourFormat(view.context)
        if (format == null || formatLocale != locale || format24Hour != use24Hour) {
            val time = if (use24Hour) "Hm" else "hm"
            val pattern = DateFormat.getBestDateTimePattern(
                locale, (if (options.weekday) "EEE" else "") + time + (if (showSeconds) "s" else ""),
            )
            format = SimpleDateFormat(pattern, locale)
            formatLocale = locale
            format24Hour = use24Hour
        }
        val formatter = checkNotNull(format)
        val zone = TimeZone.getDefault()
        if (formatter.timeZone != zone) formatter.timeZone = zone
        val seconds = FieldPosition(java.text.DateFormat.SECOND_FIELD)
        val time = formatter.format(Date(now), StringBuffer(), seconds).toString()
        val styled = showSeconds && dimSeconds && seconds.endIndex > seconds.beginIndex
        if (styled) {
            val tint = view.currentTextColor
            val dim = Color.argb((Color.alpha(tint) * 0.55f).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint))
            view.text = SpannableString(time).apply {
                setSpan(ForegroundColorSpan(dim), seconds.beginIndex, seconds.endIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (view.text.toString() != time || view.text is android.text.Spanned) {
            view.text = time
        }
        if (view.contentDescription != time) view.contentDescription = time
    }
}
