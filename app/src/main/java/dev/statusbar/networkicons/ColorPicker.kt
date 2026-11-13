package dev.statusbar.networkicons

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.InputFilter
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

internal fun colorHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

internal fun showColorPicker(
    context: Context,
    title: Int,
    initial: Int,
    useSystemColor: (() -> Unit)? = null,
    onColor: (Int) -> Unit,
) {
    val hsv = FloatArray(3)
    Color.colorToHSV(initial, hsv)
    val density = context.resources.displayMetrics.density
    val preview = View(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val hex = EditText(context).apply {
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        keyListener = DigitsKeyListener.getInstance("0123456789abcdefABCDEF#")
        filters = arrayOf(InputFilter.LengthFilter(7))
        setHint(R.string.hex_color)
    }

    fun updatePreview() {
        val color = Color.HSVToColor(hsv)
        preview.setBackgroundColor(color)
        hex.setText(colorHex(color))
        hex.error = null
    }

    val palette = ColorPalette(context, hsv, ::updatePreview)
    val hue = SeekBar(context).apply {
        max = 359
        progress = hsv[0].roundToInt().coerceIn(0, max)
        contentDescription = context.getString(R.string.hue)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    hsv[0] = value.toFloat()
                    palette.updateHue()
                    updatePreview()
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }
    updatePreview()

    val padding = (20 * density).roundToInt()
    val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
        setPadding(padding, (8 * density).roundToInt(), padding, 0)
        addView(palette, LinearLayout.LayoutParams(-1, (170 * density).roundToInt()))
        addView(TextView(context).apply { setText(R.string.hue) })
        addView(hue)
        addView(preview, LinearLayout.LayoutParams(-1, (28 * density).roundToInt()))
        addView(hex)
        requestFocus()
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(controls) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, null)
        .apply {
            if (useSystemColor != null) {
                setNeutralButton(R.string.system_clock_color) { _, _ -> useSystemColor() }
            }
        }
        .create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = hex.text.toString().removePrefix("#")
        val rgb = value.toUIntOrNull(16)?.toInt()
        if (value.length == 6 && rgb != null) {
            onColor(rgb or 0xFF000000.toInt())
            dialog.dismiss()
        } else {
            hex.error = context.getString(R.string.invalid_hex_color)
        }
    }
}

private class ColorPalette(
    context: Context,
    private val hsv: FloatArray,
    private val onColorChanged: () -> Unit,
) : View(context) {
    private val saturation = Paint()
    private val brightness = Paint()
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
    }
    private val radius = resources.displayMetrics.density * 6

    init {
        // The hue slider and hex field also support keyboard and accessibility input.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = true
    }

    fun updateHue() {
        if (width == 0 || height == 0) return
        saturation.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f, Color.WHITE,
            Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP,
        )
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        brightness.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP,
        )
        updateHue()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, saturation)
        canvas.drawRect(0f, 0f, w, h, brightness)
        val x = hsv[1] * w
        val y = (1f - hsv[2]) * h
        marker.color = Color.BLACK
        canvas.drawCircle(x, y, radius, marker)
        marker.color = Color.WHITE
        canvas.drawCircle(x, y, radius * 0.65f, marker)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
            else -> return true
        }
        hsv[1] = (event.x / width).coerceIn(0f, 1f)
        hsv[2] = 1f - (event.y / height).coerceIn(0f, 1f)
        invalidate()
        onColorChanged()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
