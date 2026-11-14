package dev.statusbar.networkicons

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.roundToInt

internal class StatusBarEffect(private val host: View) : Drawable(), View.OnAttachStateChangeListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var animator: ValueAnimator? = null
    private var fraction = 0f
    private var rain = false

    fun play(rain: Boolean) {
        stop()
        if (!host.isAttachedToWindow || !host.isShown || host.width <= 0 || host.height <= 0) return
        this.rain = rain
        fraction = 0f
        setBounds(0, 0, host.width, host.height)
        host.overlay.add(this)
        host.addOnAttachStateChangeListener(this)
        val effect = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (rain) 900L else 1300L
            interpolator = LinearInterpolator()
            addUpdateListener {
                fraction = it.animatedFraction
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = stop()
            })
        }
        animator = effect
        effect.start()
    }

    fun stop() {
        val effect = animator ?: return
        animator = null
        effect.removeAllListeners()
        effect.removeAllUpdateListeners()
        effect.cancel()
        host.overlay.remove(this)
        host.removeOnAttachStateChangeListener(this)
    }

    override fun draw(canvas: Canvas) {
        val d = host.resources.displayMetrics.density
        if (rain) {
            paint.color = 0xFF42A5F5.toInt()
            for (index in 0..2) {
                val time = (fraction - index * 0.12f) / 0.7f
                if (time !in 0f..1f) continue
                val x = (3 + index * 5) * d
                val y = (1 + time * 10) * d
                paint.alpha = ((1f - time) * 255).roundToInt()
                canvas.drawOval(x, y, x + 2 * d, y + 4 * d, paint)
            }
            return
        }
        paint.color = 0xFFFFC759.toInt()
        val center = bounds.right - 10 * d
        val y = 7 * d
        if (fraction < 0.62f) {
            val progress = fraction / 0.62f
            for (index in 0..8) {
                val start = center * index / 9
                canvas.drawCircle(start + (center - start) * progress, y, (1.2f + progress) * d, paint)
            }
        } else {
            val fade = (1f - fraction) / 0.38f
            val radius = (3 + fade * 4) * d
            paint.alpha = (fade * 255).roundToInt()
            path.rewind()
            path.moveTo(center, y - radius)
            path.lineTo(center + radius / 4, y - radius / 4)
            path.lineTo(center + radius, y)
            path.lineTo(center + radius / 4, y + radius / 4)
            path.lineTo(center, y + radius)
            path.lineTo(center - radius / 4, y + radius / 4)
            path.lineTo(center - radius, y)
            path.lineTo(center - radius / 4, y - radius / 4)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = stop()
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated by Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
