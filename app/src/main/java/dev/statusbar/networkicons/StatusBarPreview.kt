package dev.statusbar.networkicons

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal fun showStatusBarPreview(
    context: Context, options: StatusBarOptions, batteryColor: Int, chargingAnimation: ChargingAnimation,
) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(12))
    }
    val bar = FrameLayout(context).apply { setBackgroundColor(0xFF26332D.toInt()) }
    body.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))
    val clock = TextView(context).apply {
        text = "09:41"
        textSize = 15f
        setTextColor(Color.WHITE)
    }
    val weather = TextView(context).apply {
        text = "1小时内降雨 80%"
        textSize = 13f
        setPadding(dp(16), 0, 0, 0)
        setTextColor(Color.WHITE)
    }
    bar.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply {
        marginStart = dp(10)
    })
    bar.addView(weather, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
        marginEnd = dp(10)
    })

    val flash = NotificationFlash(bar)
    val rain = StatusBarEffect(weather)
    val finale = StatusBarEffect(bar)
    val line = BatteryLine(bar, batteryColor, options.batteryColors, chargingAnimation, true, false, false)
    val stopCharge = Runnable { line.uninstall() }
    fun action(label: String, play: () -> Unit) {
        body.addView(Button(context).apply {
            text = label
            setOnClickListener { play() }
        })
    }
    action("试闪一次") { flash.start(options) }
    action("预览充电动画") {
        bar.removeCallbacks(stopCharge)
        line.previewCharge()
        bar.postDelayed(stopCharge, 3000)
    }
    action("预览充满电收尾") { finale.play(rain = false) }
    action("预览降雨提醒") { rain.play(rain = true) }
    AlertDialog.Builder(context).setTitle("状态栏模拟预览")
        .setView(body)
        .setPositiveButton(android.R.string.ok, null)
        .show().setOnDismissListener {
            bar.removeCallbacks(stopCharge)
            line.uninstall()
            flash.stop()
            rain.stop()
            finale.stop()
        }
}
