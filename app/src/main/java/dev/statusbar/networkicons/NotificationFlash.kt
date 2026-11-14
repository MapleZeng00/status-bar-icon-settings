package dev.statusbar.networkicons

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

internal class NotificationFlash(private val host: View) : Drawable(), View.OnAttachStateChangeListener {
    private val paint = Paint()
    private var animator: ValueAnimator? = null
    private var strength = 0f
    private var colors = 0 to 0
    private var gradientWidth = 0

    fun start(options: StatusBarOptions) {
        stop()
        if (!host.isShown || host.width <= 0 || host.height <= 0) return
        colors = options.notificationFlashStart to options.notificationFlashEnd
        gradientWidth = 0
        setBounds(0, 0, host.width, host.height)
        host.overlay.add(this)
        host.addOnAttachStateChangeListener(this)
        val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = options.notificationFlashSeconds * 1000L
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val phase = animation.currentPlayTime / 1200.0 * 2 * PI
                val fade = ((duration - animation.currentPlayTime) / 260f).coerceIn(0f, 1f)
                strength = ((1 - cos(phase)) / 2).toFloat() * fade
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = stop()
            })
        }
        strength = 0f
        animator = pulse
        pulse.start()
    }

    fun stop() {
        val active = animator ?: return
        animator = null
        active.removeAllListeners()
        active.removeAllUpdateListeners()
        active.cancel()
        host.overlay.remove(this)
        host.removeOnAttachStateChangeListener(this)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || strength <= 0f || host.windowVisibility != View.VISIBLE) return
        val width = bounds.width()
        if (gradientWidth != width) {
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors.first, colors.second, Shader.TileMode.CLAMP)
            gradientWidth = width
        }
        paint.alpha = (46 * strength).roundToInt()
        canvas.drawRect(bounds, paint)
        paint.alpha = (165 * strength).roundToInt()
        val edge = 3 * host.resources.displayMetrics.density
        canvas.drawRect(0f, 0f, width.toFloat(), edge.coerceAtMost(bounds.bottom.toFloat()), paint)
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = stop()
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated by Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
