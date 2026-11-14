package dev.statusbar.networkicons

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Draws in the status-bar overlay without participating in layout or touch dispatch. */
internal class BatteryLine(
    private val host: View,
    private val color: Int,
    private val batteryColors: Boolean,
    private val animation: ChargingAnimation,
    private val showBattery: Boolean,
    private val hourlyStar: Boolean,
    private val chargeParticles: Boolean,
    private val chargeFinale: Boolean = false,
) : Drawable(), View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private val paint = Paint().apply { this.color = color }
    private val animationPath = Path()
    private val gradientPosition = Matrix()
    private val lightGradient by lazy { createGradient(Color.WHITE) }
    private val darkGradient by lazy { createGradient(Color.BLACK) }

    private fun createGradient(highlight: Int): LinearGradient = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(highlight and 0x00FFFFFF, highlight, highlight, highlight and 0x00FFFFFF),
        floatArrayOf(0f, 0.42f, 0.58f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val nextFrame = Runnable { invalidateSelf() }
    private var charging = false
    private var animationStart = 0L
    private var thickness = 1
    private var registered = false
    private var previousLevel: Int? = null
    private var plugged = false
    private var finalePlayed = false
    private val finale = StatusBarEffect(host)
    private var burstStart = 0L
    private var starStart = 0L
    private var lastStarHour: LocalDateTime? = null
    private val power = host.context.getSystemService(PowerManager::class.java)
    private val starCheck = Runnable { checkHour() }
    var timer: TimerProgress? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateLevel(intent)
        }
    }

    fun uninstall() {
        host.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(host)
    }

    fun install() {
        host.addOnAttachStateChangeListener(this)
        if (host.isAttachedToWindow) onViewAttachedToWindow(host)
    }

    override fun onViewAttachedToWindow(view: View) {
        if (registered) return
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = if (Build.VERSION.SDK_INT >= 33) {
                view.context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                view.context.registerReceiver(receiver, filter)
            }
            registered = true
            level = 0
            previousLevel = null
            plugged = false
            burstStart = 0L
            starStart = 0L
            charging = false
            sticky?.let(::updateLevel)
            resize()
            view.overlay.add(this)
            view.addOnLayoutChangeListener(this)
            if (hourlyStar) scheduleHour()
            Log.i(LOG_TAG, "Battery line attached")
        } catch (error: RuntimeException) {
            onViewDetachedFromWindow(view)
            Log.w(LOG_TAG, "Unable to attach battery line", error)
        }
    }

    override fun onViewDetachedFromWindow(view: View) {
        finale.stop()
        unscheduleSelf(nextFrame)
        view.removeCallbacks(starCheck)
        view.removeOnLayoutChangeListener(this)
        view.overlay.remove(this)
        if (registered) {
            registered = false
            try {
                view.context.unregisterReceiver(receiver)
            } catch (error: IllegalArgumentException) {
                Log.w(LOG_TAG, "Battery receiver already unregistered", error)
            }
        }
    }

    override fun onLayoutChange(
        view: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
    ) {
        resize()
    }

    private fun resize() {
        val density = host.resources.displayMetrics.density
        thickness = (2 * density).roundToInt().coerceAtLeast(1)
        val height = when (if (charging) animation else ChargingAnimation.OFF) {
            ChargingAnimation.OFF -> thickness
            ChargingAnimation.GRADIENT -> (5 * density).roundToInt()
            ChargingAnimation.WAVE -> (9 * density).roundToInt()
            ChargingAnimation.COMET, ChargingAnimation.DOUBLE_SWEEP, ChargingAnimation.BREATH ->
                (5 * density).roundToInt()
        }
        val effectHeight = if (hourlyStar || chargeParticles) (15 * density).roundToInt() else 0
        setBounds(0, 0, host.width, minOf(host.height, maxOf(height, effectHeight)))
    }

    private fun updateLevel(intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
        val remaining = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
        val nowPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (!nowPlugged) {
            finalePlayed = false
            finale.stop()
        }
        if (remaining in 0..scale && scale > 0) {
            val next = (remaining.toDouble() / scale * FULL_LEVEL).roundToInt()
            val previous = previousLevel
            if (plugged && nowPlugged && previous != null) {
                val reachedFull = previous < FULL_LEVEL && next == FULL_LEVEL
                val interactive = power?.isInteractive == true
                if (chargeFinale && reachedFull && !finalePlayed) {
                    finalePlayed = true
                    if (interactive) finale.play(rain = false)
                } else if (chargeParticles && interactive &&
                    ((previous < 8000 && next >= 8000) || (reachedFull && !chargeFinale))
                ) burstStart = SystemClock.uptimeMillis()
            }
            level = next
            previousLevel = next
        }
        plugged = nowPlugged
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val nowCharging = plugged && status == BatteryManager.BATTERY_STATUS_CHARGING
        if (nowCharging != charging) {
            charging = nowCharging
            animationStart = SystemClock.uptimeMillis()
            if (!charging) unscheduleSelf(nextFrame)
            resize()
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val now = SystemClock.uptimeMillis()
        val width = bounds.width() * (level.toFloat() / FULL_LEVEL)
        val remaining = timer?.let { it.end - SystemClock.elapsedRealtime() } ?: 0
        val interactive = power?.isInteractive == true
        val burst = interactive && burstStart != 0L && now - burstStart < 900
        val star = interactive && starStart != 0L && now - starStart < 950
        val activeTimer = timer
        if (activeTimer != null && remaining > 0) {
            val fraction = (remaining.toFloat() / activeTimer.duration).coerceIn(0f, 1f)
            paint.color = if (remaining <= 10_000) 0xFFFF8A24.toInt() else 0xFF42A5F5.toInt()
            canvas.drawRect(0f, 0f, bounds.width() * fraction, thickness.toFloat(), paint)
        } else if (showBattery && width > 0f) {
            drawBattery(canvas, width, now, interactive)
        }
        if (star) drawStar(canvas, (now - starStart) / 950f)
        if (burst) drawBurst(canvas, width, (now - burstStart) / 900f)
        unscheduleSelf(nextFrame)
        if (interactive && (star || burst || remaining > 0 ||
                (showBattery && charging && animation != ChargingAnimation.OFF))
        ) scheduleSelf(nextFrame, now + FRAME_MS)
    }

    private fun drawBattery(canvas: Canvas, width: Float, now: Long, interactive: Boolean) {
        val alpha = paint.alpha
        paint.color = if (!batteryColors) color else when {
            level <= 1500 -> 0xFFD32F2F.toInt()
            level <= 3500 -> 0xFFF57C00.toInt()
            else -> 0xFF32A852.toInt()
        }
        paint.alpha = alpha

        val progress = ((now - animationStart) % CYCLE_MS).toFloat() / CYCLE_MS
        val mode = if (charging && interactive) animation else ChargingAnimation.OFF
        val save = canvas.save()
        val drawHeight = when (mode) {
            ChargingAnimation.OFF -> thickness
            ChargingAnimation.WAVE -> (9 * host.resources.displayMetrics.density).roundToInt()
            else -> (5 * host.resources.displayMetrics.density).roundToInt()
        }.coerceAtMost(bounds.height())
        animationHeight = drawHeight
        canvas.clipRect(0f, 0f, bounds.right.toFloat(), drawHeight.toFloat())
        when (mode) {
            ChargingAnimation.OFF -> canvas.drawRect(0f, 0f, width, drawHeight.toFloat(), paint)
            ChargingAnimation.GRADIENT -> drawGradient(canvas, width, progress)
            ChargingAnimation.WAVE -> drawWave(canvas, width, progress)
            ChargingAnimation.COMET -> drawComet(canvas, width, progress)
            ChargingAnimation.DOUBLE_SWEEP -> drawDoubleSweep(canvas, width, progress)
            ChargingAnimation.BREATH -> drawBreath(canvas, width, progress)
        }
        canvas.restoreToCount(save)
    }

    private fun scheduleHour() {
        val now = LocalDateTime.now()
        val untilHour = java.time.Duration.between(
            now, now.withMinute(0).withSecond(0).withNano(0).plusHours(1),
        ).toMillis()
        host.postDelayed(starCheck, minOf(60_000L, untilHour.coerceAtLeast(1L) + 40))
    }

    private fun checkHour() {
        val now = LocalDateTime.now()
        val hour = now.withMinute(0).withSecond(0).withNano(0)
        if (now.hour in 7..21 && now.minute == 0 && now.second <= 2 &&
            hour != lastStarHour && power?.isInteractive == true && host.isShown
        ) {
            lastStarHour = hour
            starStart = SystemClock.uptimeMillis()
            invalidateSelf()
        }
        if (registered) scheduleHour()
    }

    private fun drawStar(canvas: Canvas, progress: Float) {
        val d = host.resources.displayMetrics.density
        val x = (bounds.width() + 14 * d) * progress - 7 * d
        val y = 6 * d
        paint.color = 0xFFFFC759.toInt()
        paint.isAntiAlias = true
        animationPath.rewind()
        animationPath.moveTo(x, y - 5 * d)
        animationPath.lineTo(x + d, y - d)
        animationPath.lineTo(x + 5 * d, y)
        animationPath.lineTo(x + d, y + d)
        animationPath.lineTo(x, y + 5 * d)
        animationPath.lineTo(x - d, y + d)
        animationPath.lineTo(x - 5 * d, y)
        animationPath.lineTo(x - d, y - d)
        animationPath.close()
        canvas.drawPath(animationPath, paint)
        paint.isAntiAlias = false
    }

    private fun drawBurst(canvas: Canvas, width: Float, progress: Float) {
        val d = host.resources.displayMetrics.density
        val center = width.coerceIn(0f, (bounds.width() - 4 * d).coerceAtLeast(0f))
        paint.isAntiAlias = true
        for (index in 0 until 9) {
            val angle = Math.PI * index / 8
            val x = center + cos(angle).toFloat() * (3 + 22 * progress) * d
            val y = (2 + sin(angle).toFloat() * 11 * progress) * d
            paint.color = if (index % 2 == 0) 0xFF63DE88.toInt() else 0xFFFFC759.toInt()
            paint.alpha = (255 * (1 - progress)).roundToInt()
            canvas.drawCircle(x, y, (1.8f - progress) * d, paint)
        }
        paint.alpha = 255
        paint.isAntiAlias = false
    }

    private var animationHeight = 0

    fun previewCharge() {
        level = 7600
        charging = true
        animationStart = SystemClock.uptimeMillis()
        resize()
        host.overlay.add(this)
    }

    private fun drawGradient(canvas: Canvas, width: Float, progress: Float) {
        val band = (76 * host.resources.displayMetrics.density).coerceAtMost(width)
        val left = (width + band) * progress - band
        val base = minOf(thickness, animationHeight).toFloat()
        val bottom = animationHeight.toFloat()
        canvas.drawRect(0f, 0f, width, base, paint)

        gradientPosition.setScale(band, 1f)
        gradientPosition.postTranslate(left, 0f)
        val gradient = if (Color.luminance(paint.color) > 0.65f) darkGradient else lightGradient
        gradient.setLocalMatrix(gradientPosition)
        animationPath.rewind()
        animationPath.moveTo(left, 0f)
        animationPath.lineTo(left + band, 0f)
        animationPath.lineTo(left + band, base)
        animationPath.cubicTo(
            left + band * 0.78f, base, left + band * 0.75f, bottom,
            left + band * 0.5f, bottom,
        )
        animationPath.cubicTo(
            left + band * 0.25f, bottom, left + band * 0.22f, base, left, base,
        )
        animationPath.close()
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width, bottom)
        paint.shader = gradient
        paint.isAntiAlias = true
        canvas.drawPath(animationPath, paint)
        paint.isAntiAlias = false
        paint.shader = null
        canvas.restoreToCount(save)
    }

    private fun drawWave(canvas: Canvas, width: Float, progress: Float) {
        val wavelength = (54 * host.resources.displayMetrics.density).coerceAtMost(width)
        val half = wavelength / 2f
        val baseline = animationHeight / 2f
        val amplitude = (baseline - thickness / 2f).coerceAtLeast(0f) * 0.86f
        val controlHeight = amplitude * 4f / 3f
        var x = wavelength * (progress - 1f)

        animationPath.rewind()
        animationPath.moveTo(x, baseline)
        while (x < width) {
            animationPath.cubicTo(
                x + half / 3f, baseline - controlHeight,
                x + half * 2f / 3f, baseline - controlHeight,
                x + half, baseline,
            )
            animationPath.cubicTo(
                x + half * 4f / 3f, baseline + controlHeight,
                x + half * 5f / 3f, baseline + controlHeight,
                x + wavelength, baseline,
            )
            x += wavelength
        }

        val save = canvas.save()
        canvas.clipRect(0f, 0f, width, animationHeight.toFloat())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thickness.toFloat()
        paint.isAntiAlias = true
        canvas.drawPath(animationPath, paint)
        paint.isAntiAlias = false
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun drawComet(canvas: Canvas, width: Float, progress: Float) {
        val base = minOf(thickness, animationHeight).toFloat()
        canvas.drawRect(0f, 0f, width, base, paint)
        val unit = host.resources.displayMetrics.density
        val head = (width + 42 * unit) * progress
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width, animationHeight.toFloat())
        paint.isAntiAlias = true
        val alpha = paint.alpha
        for (index in 5 downTo 0) {
            val radius = (animationHeight / 2f) * (1f - index * 0.12f)
            paint.alpha = alpha * (6 - index) / 6
            canvas.drawCircle(head - index * 7 * unit, animationHeight / 2f, radius, paint)
        }
        paint.alpha = alpha
        paint.isAntiAlias = false
        canvas.restoreToCount(save)
    }

    private fun drawDoubleSweep(canvas: Canvas, width: Float, progress: Float) {
        drawGradient(canvas, width, progress)
        drawGradient(canvas, width, 1f - progress)
    }

    private fun drawBreath(canvas: Canvas, width: Float, progress: Float) {
        val strength = (1f - kotlin.math.cos(progress * 2 * Math.PI).toFloat()) / 2f
        val base = minOf(thickness, animationHeight).toFloat()
        canvas.drawRect(0f, 0f, width, base, paint)
        val alpha = paint.alpha
        paint.alpha = (alpha * strength).roundToInt()
        val height = base + (animationHeight - base) * strength
        canvas.drawRect(0f, base, width, height, paint)
        paint.alpha = alpha
    }

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated by Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val FULL_LEVEL = 10_000
        const val CYCLE_MS = 1_800L
        const val FRAME_MS = 33L
    }
}
