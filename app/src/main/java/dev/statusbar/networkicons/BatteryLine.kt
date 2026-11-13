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
import android.os.SystemClock
import android.util.Log
import android.view.View
import kotlin.math.roundToInt

/** Draws in the status-bar overlay without participating in layout or touch dispatch. */
internal class BatteryLine(
    private val host: View,
    color: Int,
    private val animation: ChargingAnimation,
) : Drawable(), View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private val paint = Paint().apply { this.color = color }
    private val animationPath = Path()
    private val gradientPosition = Matrix()
    private val gradient by lazy {
        val toward = if (Color.luminance(color) > 0.65f) Color.BLACK else Color.WHITE
        val transparent = toward and 0x00FFFFFF
        LinearGradient(
            0f, 0f, 1f, 0f,
            intArrayOf(transparent, toward, toward, transparent),
            floatArrayOf(0f, 0.42f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val nextFrame = Runnable { invalidateSelf() }
    private var charging = false
    private var animationStart = 0L
    private var thickness = 1
    private var registered = false
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
            charging = false
            sticky?.let(::updateLevel)
            resize()
            view.overlay.add(this)
            view.addOnLayoutChangeListener(this)
            Log.i(LOG_TAG, "Battery line attached")
        } catch (error: RuntimeException) {
            onViewDetachedFromWindow(view)
            Log.w(LOG_TAG, "Unable to attach battery line", error)
        }
    }

    override fun onViewDetachedFromWindow(view: View) {
        unscheduleSelf(nextFrame)
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
        setBounds(0, 0, host.width, minOf(host.height, height))
    }

    private fun updateLevel(intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
        val remaining = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
        if (remaining in 0..scale && scale > 0) {
            level = (remaining.toDouble() / scale * FULL_LEVEL).roundToInt()
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
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
        val width = bounds.width() * (level.toFloat() / FULL_LEVEL)
        if (width <= 0f || bounds.isEmpty) return

        val now = SystemClock.uptimeMillis()
        val progress = ((now - animationStart) % CYCLE_MS).toFloat() / CYCLE_MS
        val mode = if (charging) animation else ChargingAnimation.OFF
        when (mode) {
            ChargingAnimation.OFF -> canvas.drawRect(0f, 0f, width, bounds.bottom.toFloat(), paint)
            ChargingAnimation.GRADIENT -> drawGradient(canvas, width, progress)
            ChargingAnimation.WAVE -> drawWave(canvas, width, progress)
            ChargingAnimation.COMET -> drawComet(canvas, width, progress)
            ChargingAnimation.DOUBLE_SWEEP -> drawDoubleSweep(canvas, width, progress)
            ChargingAnimation.BREATH -> drawBreath(canvas, width, progress)
        }
        if (mode != ChargingAnimation.OFF) {
            // Only request another frame when the overlay is actually being drawn.
            unscheduleSelf(nextFrame)
            scheduleSelf(nextFrame, now + FRAME_MS)
        }
    }

    private fun drawGradient(canvas: Canvas, width: Float, progress: Float) {
        val band = (76 * host.resources.displayMetrics.density).coerceAtMost(width)
        val left = (width + band) * progress - band
        val base = minOf(thickness, bounds.height()).toFloat()
        val bottom = bounds.bottom.toFloat()
        canvas.drawRect(0f, 0f, width, base, paint)

        gradientPosition.setScale(band, 1f)
        gradientPosition.postTranslate(left, 0f)
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
        val baseline = bounds.bottom / 2f
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
        canvas.clipRect(0f, 0f, width, bounds.bottom.toFloat())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thickness.toFloat()
        paint.isAntiAlias = true
        canvas.drawPath(animationPath, paint)
        paint.isAntiAlias = false
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun drawComet(canvas: Canvas, width: Float, progress: Float) {
        val base = minOf(thickness, bounds.height()).toFloat()
        canvas.drawRect(0f, 0f, width, base, paint)
        val unit = host.resources.displayMetrics.density
        val head = (width + 42 * unit) * progress
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width, bounds.bottom.toFloat())
        paint.isAntiAlias = true
        val alpha = paint.alpha
        for (index in 5 downTo 0) {
            val radius = (bounds.height() / 2f) * (1f - index * 0.12f)
            paint.alpha = alpha * (6 - index) / 6
            canvas.drawCircle(head - index * 7 * unit, bounds.height() / 2f, radius, paint)
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
        val base = minOf(thickness, bounds.height()).toFloat()
        canvas.drawRect(0f, 0f, width, base, paint)
        val alpha = paint.alpha
        paint.alpha = (alpha * strength).roundToInt()
        val height = base + (bounds.height() - base) * strength
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
