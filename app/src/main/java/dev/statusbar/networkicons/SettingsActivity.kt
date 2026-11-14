package dev.statusbar.networkicons

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.libxposed.service.XposedService

class SettingsActivity : Activity() {
    private val app get() = application as SettingsApp
    private lateinit var connection: TextView
    private lateinit var feedback: TextView
    private lateinit var mobile: Switch
    private lateinit var wifi: Switch
    private lateinit var noSim: Switch
    private lateinit var battery: Switch
    private lateinit var speedThreshold: Button
    private var selectedSpeedThreshold: Int? = null
    private lateinit var batteryLine: Switch
    private lateinit var lineColor: Button
    private lateinit var chargingAnimation: Button
    private var selectedAnimation = ChargingAnimation.OFF
    private var selectedColor = DEFAULT_LINE_COLOR
    private lateinit var clockSeconds: Switch
    private lateinit var clockColor: Button
    private var selectedClockColor: Int? = null
    private lateinit var optionsButton: Button
    private var options = StatusBarOptions()
    private lateinit var debug: Switch
    private var prefs: SharedPreferences? = null
    private var generation = 0
    private val observer: (XposedService?) -> Unit = ::readService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.settings_background))
        }
        page.addView(TextView(this).apply {
            setText(R.string.app_name)
            textSize = 26f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(68)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(getColor(R.color.settings_header))
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        page.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)

        val status = settingsCard(content, filled = true)
        connection = TextView(this).apply {
            textSize = 15f
            setTextColor(getColor(R.color.settings_accent))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }.also(status::addView)
        feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.settings_secondary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }.also(status::addView)

        val icons = settingsCard(content, R.string.section_icons)
        mobile = addToggle(icons, R.string.hide_mobile)
        wifi = addToggle(icons, R.string.hide_wifi)
        noSim = addToggle(icons, R.string.hide_no_sim)
        battery = addToggle(icons, R.string.hide_battery)
        speedThreshold = addAction(icons) { chooseSpeedThreshold() }
        updateSpeedThresholdButton()

        val line = settingsCard(content, R.string.section_line, filled = true)
        batteryLine = addToggle(line, R.string.battery_line)
        lineColor = addAction(line) { chooseLineColor() }
        chargingAnimation = addAction(line) { chooseChargingAnimation() }
        updateChargingButton()
        updateColorButton()

        val clock = settingsCard(content, R.string.section_clock)
        clockSeconds = addToggle(clock, R.string.clock_seconds)
        clockColor = addAction(clock) { chooseClockColor() }
        updateClockColorButton()

        val advanced = settingsCard(content, R.string.section_advanced)
        optionsButton = addAction(advanced) {
            showStatusBarOptions(this, options, selectedColor, selectedAnimation) {
                options = it
                updateColorButton()
                updateBatteryButtons()
                saveSettings()
                if (it.calendarCountdown &&
                    checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 1)
            }
        }.apply { setText(R.string.status_bar_options) }
        addAction(advanced) { showStatusBarPreview(this, options, selectedColor, selectedAnimation) }
            .apply { text = "模拟预览" }
        debug = addToggle(advanced, R.string.debug_logs)
        advanced.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.settings_secondary))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(6))
            setText(R.string.scope_info)
        })
        app.observe(observer)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun settingsCard(parent: LinearLayout, title: Int = 0, filled: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(if (filled) R.color.settings_panel else R.color.settings_background))
                if (!filled) setStroke(dp(1), getColor(R.color.settings_outline))
            }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            })
            if (title != 0) {
                addView(TextView(this@SettingsActivity).apply {
                    setText(title)
                    textSize = 19f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(0, 0, 0, dp(4))
                    isAccessibilityHeading = true
                })
            }
        }
    }

    private fun addToggle(parent: LinearLayout, label: Int): Switch = Switch(this).apply {
        setText(label)
        textSize = 15f
        minHeight = dp(52)
        switchPadding = dp(14)
        setOnCheckedChangeListener { _, _ ->
            updateBatteryButtons()
            saveSettings()
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addAction(parent: LinearLayout, onClick: () -> Unit): Button =
        Button(this).apply {
            isAllCaps = false
            textSize = 15f
            minHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            compoundDrawablePadding = dp(8)
            stateListAnimator = null
            setTextColor(getColor(R.color.settings_accent))
            background = RippleDrawable(
                ColorStateList.valueOf(0x2430664A),
                GradientDrawable().apply {
                    cornerRadius = dp(32).toFloat()
                    setColor(getColor(R.color.settings_button))
                },
                null,
            )
            setOnClickListener { onClick() }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
            })
        }

    private fun showFeedback(message: Int = 0) {
        feedback.visibility = if (message == 0) View.GONE else View.VISIBLE
        if (message != 0) feedback.setText(message) else feedback.text = ""
    }

    override fun onDestroy() {
        generation++
        app.removeObserver(observer)
        super.onDestroy()
    }

    private fun readService(service: XposedService?) {
        val request = ++generation
        prefs = null
        setControls(false)
        showFeedback()
        connection.setText(R.string.waiting)
        if (service == null) return

        app.io.execute {
            try {
                val api = service.apiVersion
                if (api < 102 || (service.frameworkProperties and XposedService.PROP_CAP_REMOTE) == 0L) {
                    runOnUiThread {
                        if (request == generation) connection.setText(R.string.incompatible)
                    }
                    return@execute
                }
                val remote = service.getRemotePreferences(PREF_GROUP)
                val settings = IconSettings.read(remote)
                val status = getString(
                    R.string.ready, service.frameworkName, service.frameworkVersion, api,
                )
                runOnUiThread {
                    if (request != generation) return@runOnUiThread
                    connection.text = status
                    mobile.isChecked = settings.hideMobile
                    wifi.isChecked = settings.hideWifi
                    noSim.isChecked = settings.hideNoSim
                    battery.isChecked = settings.hideBattery
                    selectedSpeedThreshold = settings.networkSpeedThreshold
                    updateSpeedThresholdButton()
                    batteryLine.isChecked = settings.batteryLine
                    options = settings.options
                    selectedColor = settings.batteryLineColor
                    updateColorButton()
                    selectedAnimation = settings.chargingAnimation
                    updateChargingButton()
                    clockSeconds.isChecked = settings.clockSeconds
                    selectedClockColor = settings.clockColor
                    updateClockColorButton()
                    debug.isChecked = settings.debug
                    prefs = remote
                    setControls(true)
                }
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Unable to read settings", error)
                runOnUiThread {
                    if (request == generation) connection.setText(R.string.read_failed)
                }
            }
        }
    }

    private fun saveSettings() {
        val remote = prefs ?: return
        val request = generation
        val settings = IconSettings(
            hideMobile = mobile.isChecked,
            hideWifi = wifi.isChecked,
            hideNoSim = noSim.isChecked,
            hideBattery = battery.isChecked,
            networkSpeedThreshold = selectedSpeedThreshold,
            batteryLine = batteryLine.isChecked,
            batteryLineColor = selectedColor,
            chargingAnimation = selectedAnimation,
            clockSeconds = clockSeconds.isChecked,
            clockColor = selectedClockColor,
            debug = debug.isChecked,
            options = options,
        )
        showFeedback(R.string.saving)
        app.io.execute {
            val saved = try {
                settings.save(remote)
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Unable to save settings", error)
                false
            }
            runOnUiThread {
                if (request != generation) return@runOnUiThread
                showFeedback(if (saved) R.string.saved else R.string.save_failed)
            }
        }
    }

    private fun chooseSpeedThreshold() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setHint(R.string.speed_threshold_unit)
            setText(selectedSpeedThreshold?.toString().orEmpty())
            selectAll()
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@SettingsActivity).apply {
                setText(R.string.speed_threshold_info)
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.choose_speed_threshold)
            .setView(panel)
            .setNeutralButton(R.string.system_clock_color) { _, _ -> setSpeedThreshold(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value < 0) {
                input.error = getString(R.string.speed_threshold_invalid)
            } else {
                setSpeedThreshold(value)
                dialog.dismiss()
            }
        }
    }

    private fun setSpeedThreshold(value: Int?) {
        selectedSpeedThreshold = value
        updateSpeedThresholdButton()
        saveSettings()
    }

    private fun updateSpeedThresholdButton() {
        speedThreshold.text = selectedSpeedThreshold?.let {
            getString(R.string.speed_threshold_value, it)
        } ?: getString(R.string.speed_threshold_default)
    }

    private fun chooseLineColor() {
        showColorPicker(this, R.string.choose_line_color, selectedColor) { color ->
            selectedColor = color
            updateColorButton()
            saveSettings()
        }
    }

    private fun updateColorButton() {
        val automatic = options.batteryColors
        lineColor.text = if (automatic) getString(R.string.battery_line_color_automatic)
            else getString(R.string.battery_line_color, colorHex(selectedColor))
        lineColor.setCompoundDrawablesRelative(
            if (automatic) null else colorSwatch(selectedColor), null, null, null,
        )
    }

    private fun colorSwatch(color: Int): GradientDrawable {
        val size = (16 * resources.displayMetrics.density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(1, 0xFF808080.toInt())
            setBounds(0, 0, size, size)
        }
    }

    private fun chooseChargingAnimation() {
        val names = resources.getStringArray(R.array.charging_animations)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_charging_animation)
            .setSingleChoiceItems(names, selectedAnimation.ordinal) { dialog, index ->
                selectedAnimation = ChargingAnimation.entries[index]
                updateChargingButton()
                dialog.dismiss()
                saveSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateChargingButton() {
        val names = resources.getStringArray(R.array.charging_animations)
        chargingAnimation.text = getString(R.string.charging_animation, names[selectedAnimation.ordinal])
    }

    private fun chooseClockColor() {
        showColorPicker(
            this, R.string.choose_clock_color, selectedClockColor ?: android.graphics.Color.WHITE,
            useSystemColor = { setClockColor(null) },
            onColor = { setClockColor(it) },
        )
    }

    private fun setClockColor(color: Int?) {
        selectedClockColor = color
        updateClockColorButton()
        saveSettings()
    }

    private fun updateClockColorButton() {
        val color = selectedClockColor
        val name = color?.let(::colorHex) ?: getString(R.string.system_clock_color)
        clockColor.text = getString(R.string.clock_color, name)
        clockColor.setCompoundDrawablesRelative(color?.let(::colorSwatch), null, null, null)
    }

    private fun updateBatteryButtons() {
        val enabled = batteryLine.isEnabled && batteryLine.isChecked
        lineColor.isEnabled = enabled && !options.batteryColors
        chargingAnimation.isEnabled = enabled
        lineColor.alpha = if (lineColor.isEnabled) 1f else 0.55f
        chargingAnimation.alpha = if (enabled) 1f else 0.55f
    }

    private fun setControls(enabled: Boolean) {
        mobile.isEnabled = enabled
        wifi.isEnabled = enabled
        noSim.isEnabled = enabled
        battery.isEnabled = enabled
        speedThreshold.isEnabled = enabled
        speedThreshold.alpha = if (enabled) 1f else 0.55f
        batteryLine.isEnabled = enabled
        updateBatteryButtons()
        clockSeconds.isEnabled = enabled
        clockColor.isEnabled = enabled
        clockColor.alpha = if (enabled) 1f else 0.55f
        optionsButton.isEnabled = enabled
        optionsButton.alpha = if (enabled) 1f else 0.55f
        debug.isEnabled = enabled
    }
}
