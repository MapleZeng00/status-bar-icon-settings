package dev.statusbar.networkicons

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.app.ActivityManager
import android.app.Notification
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ViewGroup
import android.widget.LinearLayout
import android.service.notification.StatusBarNotification
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NetworkIconModule : XposedModule() {
    private var isSystemUi = false
    private val started = AtomicBoolean()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var settings: IconSettings? = null
    private var prefs: SharedPreferences? = null
    private val reload = Runnable { install("Live settings") { reloadSettings() } }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(reload)
        main.post(reload)
    }

    private val clockControllers = WeakHashMap<TextView, WeakReference<StatusBarClock>>()
    private var clockUpdate: Method? = null
    private data class LineAppearance(
        val color: Int, val batteryColors: Boolean, val animation: ChargingAnimation,
        val showBattery: Boolean, val star: Boolean, val particles: Boolean,
    )
    private data class ActiveLine(val appearance: LineAppearance, val drawable: WeakReference<BatteryLine>)
    private val lineHosts = WeakHashMap<View, ActiveLine?>()
    private val icons = WeakHashMap<View, IconState>()
    private var foregroundReader: (() -> String?)? = null
    private var profile: Profile? = null
    private var readingPackages = emptySet<String>()
    private var gamingPackages = emptySet<String>()
    private var allowedPackages: Set<String>? = null
    private var secondsOverride: Boolean? = null
    private val statusBarGestures = WeakHashMap<View, GestureDetector>()
    private var speedBlocked = false
    private var slowSince: Long? = null
    private var latestSpeed: Double? = null
    private var speedFields: Pair<java.lang.reflect.Field, java.lang.reflect.Field>? = null
    private val weatherViews = WeakHashMap<View, WeakReference<StatusBarWeather>>()
    private var weatherTint: ColorStateList? = null
    private var speedMirror: WeakReference<TextView>? = null
    private val speedCheck = Runnable { refreshSpeed() }
    private val foregroundCheck = Runnable { refreshForeground() }
    private var applyingVisibility = false
    private val extras = WeakHashMap<View, WeakReference<StatusBarExtras>>()
    private val speedHistory = ArrayDeque<SpeedSample>()
    @Volatile private var timerReader: (() -> TimerProgress?)? = null
    @Volatile private var privacyReader: (() -> Set<String>)? = null
    private var timer: TimerProgress? = null
    private var foregroundApp: String? = null
    private val extrasCheck = Runnable { install("Refresh status bar extras") { refreshExtras() } }

    private enum class Icon { MOBILE, WIFI, NO_SIM, BATTERY, SPEED, CLOCK, OTHER }
    private enum class Profile { READING, GAMING }

    private data class IconState(
        val icon: Icon,
        var visibility: Int,
        var hidden: Boolean = false,
        val colors: MutableMap<TextView, ColorStateList> = WeakHashMap(),
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        isSystemUi = !param.isSystemServer && param.processName == SYSTEM_UI
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!isSystemUi || !param.isFirstPackage || param.packageName != SYSTEM_UI) return
        if (!started.compareAndSet(false, true)) return

        val remote = try {
            report(Log.INFO, "SystemUI loaded: $frameworkName $frameworkVersion ($frameworkVersionCode), API $apiVersion, Android SDK ${Build.VERSION.SDK_INT}")
            getRemotePreferences(PREF_GROUP)
        } catch (error: Exception) {
            report(Log.WARN, "Remote preferences unavailable", error)
            return
        } catch (error: LinkageError) {
            report(Log.WARN, "Framework API mismatch", error)
            return
        }

        prefs = remote
        install("Live settings listener") { remote.registerOnSharedPreferenceChangeListener(listener) }
        install("Initial settings") { settings = IconSettings.read(remote) }
        install("Icon visibility") {
            hookIconVisibility()
            install("Network icons") {
                hookNetworkIcons(param.classLoader)
                hookNetworkIconMeasurement(param.classLoader)
            }
            install("Network speed") { hookNetworkSpeed(param.classLoader) }
            install("Battery icon") { hookBatteryMeter(param.classLoader) }
        }
        install("Battery line") { hookBatteryLine(param.classLoader) }
        install("Status bar clock") { hookStatusBarClock(param.classLoader) }
        install("Status bar layout") { hookStatusBarLayout(param.classLoader) }
        install("Status bar gestures") { hookStatusBarGestures() }
        install("Foreground app detection") { prepareForegroundReader(param.classLoader) }
        install("System timer notifications") { prepareTimerReader(param.classLoader) }
        install("Privacy indicators") { preparePrivacyReader(param.classLoader) }
        main.post(reload)
    }

    private fun reloadSettings() {
        val next = IconSettings.read(prefs ?: return)
        val previous = settings
        settings = next
        if (previous?.clockSeconds != next.clockSeconds || !next.options.clockGestures) secondsOverride = null
        if (previous != next) report(Log.INFO, "Live settings received")
        if (next.debug) report(Log.DEBUG, "Status bar settings refreshed")
        readingPackages = packageNames(next.options.readingApps)
        gamingPackages = packageNames(next.options.gamingApps)
        allowedPackages = next.options.notificationApps?.let(::packageNames)
        if (!next.options.speedGraph) speedHistory.clear()
        main.removeCallbacks(foregroundCheck)
        refreshForeground(refresh = false)
        if (previous?.networkSpeedThreshold != next.networkSpeedThreshold ||
            previous?.options?.speedSmoothing != next.options.speedSmoothing
        ) {
            speedBlocked = false
            slowSince = null
        }
        refreshSpeed()
        refreshViews()
    }

    private fun refreshViews() {
        val current = settings ?: return
        icons.entries.toList().forEach { (view, state) ->
            install("Refresh icon") { applyIcon(view, state) }
        }
        lineHosts.keys.toList().forEach { view ->
            install("Refresh battery line") { updateLine(view, current) }
            install("Refresh status bar weather") { updateWeather(view, current) }
        }
        clockControllers.keys.toList().forEach { view ->
            install("Refresh status bar clock") { clockUpdate?.invoke(view) }
        }
        lineHosts.keys.toList().forEach { view ->
            view.forceLayout()
            if (view is ViewGroup) requestChildLayout(view)
            view.requestLayout()
        }
        install("Refresh status bar extras") { refreshExtras() }
    }

    private fun requestChildLayout(group: ViewGroup) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            child.forceLayout()
            if (child is ViewGroup) requestChildLayout(child)
        }
    }

    private fun hookStatusBarClock(loader: ClassLoader) {
        val clock = Class.forName(
            "com.android.systemui.statusbar.views.MiuiClock", false, loader,
        ).asSubclass(TextView::class.java)
        val methods = clock.declaredMethods.filter { method ->
            method.returnType == Void.TYPE && !Modifier.isStatic(method.modifiers) &&
                when (method.name) {
                    "updateTime", "onAttachedToWindow" -> method.parameterCount == 0
                    "onDarkChanged", "onLightDarkTintChanged", "setTextDark" -> true
                    else -> false
                }
        }
        clockUpdate = methods.single { it.name == "updateTime" }.apply { isAccessible = true }
        for (method in methods) {
            install("Status bar clock ${method.name}") {
                hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val view = chain.thisObject as? TextView
                    val controller = if (view != null && isMainClock(view)) {
                        clockControllers[view]?.get() ?: StatusBarClock(view).also {
                            clockControllers[view] = WeakReference(it)
                            it.install()
                        }
                    } else null
                    controller?.restoreSystemColor()
                    val result = chain.proceed()
                    if (view != null && controller != null && method.name != "updateTime") {
                        weatherTint = view.textColors
                        weatherViews.values.forEach { it.get()?.tint(view.textColors) }
                    }
                    if (controller != null) updateClockDisplay(controller)
                    if (view != null && controller != null) {
                        trackIcon(view, Icon.CLOCK)
                        if (method.name == "onAttachedToWindow" && settings?.options?.speedByClock == true) {
                            main.post { install("Network speed beside clock") { updateSpeedMirror() } }
                        }
                    }
                    result
                }
                if (settings?.debug == true) report(Log.DEBUG, "Status bar clock signature: $method")
            }
        }
    }

    private fun updateClockDisplay(controller: StatusBarClock) {
        val current = settings ?: return
        controller.configure(
            secondsOverride ?: current.clockSeconds,
            current.clockColor,
            current.options,
            forceMinutes = secondsOverride == false,
        )
        controller.refresh()
    }

    private fun isMainClock(view: TextView): Boolean = try {
        view.id != View.NO_ID && view.resources.getResourceEntryName(view.id) == "clock"
    } catch (_: Resources.NotFoundException) {
        false
    }

    private fun hookBatteryLine(loader: ClassLoader) {
        val prefix = "com.android.systemui.statusbar.phone."
        val hosts = listOf("MiuiPhoneStatusBarView", "PhoneStatusBarView").mapNotNull { name ->
            try {
                Class.forName(prefix + name, false, loader).asSubclass(View::class.java)
            } catch (_: ClassNotFoundException) {
                null
            }
        }
        val methods = hosts.mapNotNull { host ->
            generateSequence<Class<*>>(host) { it.superclass }
                .takeWhile { it in hosts }
                .mapNotNull { owner ->
                    owner.declaredMethods.singleOrNull {
                        it.name == "onFinishInflate" && it.parameterCount == 0 &&
                            it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
                    }
                }
                .firstOrNull()
        }.distinct()
        check(methods.isNotEmpty()) { "No supported phone status bar view" }

        for (method in methods) {
            install("Battery line ${method.declaringClass.simpleName}") {
                hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    if (view != null && view.javaClass in hosts && view !in lineHosts) {
                        lineHosts[view] = null
                        settings?.let {
                            updateLine(view, it)
                            install("Status bar weather") { updateWeather(view, it) }
                        }
                    }
                    result
                }
                if (settings?.debug == true) report(Log.DEBUG, "Battery line signature: $method")
            }
        }
    }

    private fun updateWeather(host: View, current: IconSettings) {
        if (!current.options.showWeather || profile != null) {
            weatherViews.remove(host)?.get()?.uninstall()
            return
        }
        weatherViews[host]?.get()?.let {
            it.configure(current.options.showWeatherCondition, current.options.weatherLanguage, current.options.rainNotice)
            return
        }
        val resources = host.resources
        val contentsId = resources.getIdentifier("status_bar_contents", "id", SYSTEM_UI)
        val contents = host.findViewById<View>(contentsId) ?: return
        val frame = contents.parent as? android.widget.FrameLayout ?: return
        if (contents.layoutParams !is android.widget.FrameLayout.LayoutParams) return
        val weather = StatusBarWeather(frame, contents)
        weather.configure(current.options.showWeatherCondition, current.options.weatherLanguage, current.options.rainNotice)
        weatherTint?.let(weather::tint)
        weather.install()
        weatherViews[host] = WeakReference(weather)
    }

    private fun updateLine(view: View, settings: IconSettings) {
        val options = settings.options
        val showBattery = profile == Profile.READING || (profile == null && settings.batteryLine)
        val star = profile == null && options.hourlyStar
        val particles = profile == null && options.chargeParticles
        val appearance = LineAppearance(
            settings.batteryLineColor, options.batteryColors, settings.chargingAnimation, showBattery, star, particles,
        )
        val previous = lineHosts[view]
        val enabled = showBattery || star || particles || (profile == null && options.timerLine)
        if (enabled && previous?.appearance == appearance && previous.drawable.get() != null) return
        previous?.drawable?.get()?.uninstall()
        lineHosts[view] = if (enabled) {
            val line = BatteryLine(
                view, appearance.color, appearance.batteryColors, appearance.animation, showBattery, star, particles,
            )
            line.timer = timer.takeIf { profile == null && options.timerLine }
            line.install()
            ActiveLine(appearance, WeakReference(line))
        } else null
    }

    private fun hookIconVisibility() {
        val target = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val state = if (!applyingVisibility && Looper.myLooper() == Looper.getMainLooper()) {
                icons[chain.thisObject as? View]
            } else null
            if (state != null) {
                state.visibility = chain.getArg(0) as Int
                if (state.hidden && state.visibility != View.GONE) {
                    return@intercept chain.proceed(arrayOf<Any>(View.GONE))
                }
            }
            chain.proceed()
        }
    }

    private fun hookNetworkIcons(loader: ClassLoader) {
        val manager = Class.forName("com.android.systemui.statusbar.phone.ui.IconManager", false, loader)
        val location = manager.getDeclaredField("mLocation").apply { isAccessible = true }
        val target = manager.declaredMethods.single { method ->
            val types = method.parameterTypes
            method.name == "addHolder" && types.size == 4 &&
                types[0] == Int::class.javaPrimitiveType && types[1] == String::class.java &&
                types[2] == Boolean::class.javaPrimitiveType
        }
        hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            val view = result as? View
            if (view != null && (location.get(chain.thisObject) as? Enum<*>)?.name == "HOME") {
                val icon = when (chain.getArg(1)) {
                    "mobile", "demo_mobile" -> Icon.MOBILE
                    "wifi", "demo_wifi" -> Icon.WIFI
                    "no_sim" -> Icon.NO_SIM
                    "network_speed" -> Icon.SPEED
                    else -> Icon.OTHER
                }
                trackIcon(view, icon)
            }
            result
        }
    }

    private fun hookNetworkIconMeasurement(loader: ClassLoader) {
        // MIUI measures icons using StatusIconDisplayable.isIconVisible(), even for GONE views.
        val classes = listOf(
            "com.android.systemui.statusbar.StatusBarIconView",
            "com.android.systemui.statusbar.views.NetworkSpeedView",
            "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView",
        )
        for (name in classes) {
            install("Icon measurement ${name.substringAfterLast('.')}") {
                val target = Class.forName(name, false, loader).getDeclaredMethod("isIconVisible")
                check(target.returnType == Boolean::class.javaPrimitiveType)
                hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val view = chain.thisObject as? View
                    if (view != null && Looper.myLooper() == Looper.getMainLooper() &&
                        icons[view]?.hidden == true
                    ) {
                        false
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookNetworkSpeed(loader: ClassLoader) {
        val meter = Class.forName("com.android.systemui.statusbar.views.NetworkSpeedView", false, loader)
        val number = meter.getDeclaredField("mNetworkSpeedNumberText").apply { isAccessible = true }
        val unit = meter.getDeclaredField("mNetworkSpeedUnitText").apply { isAccessible = true }
        speedFields = number to unit
        val method = meter.getDeclaredMethod("setNetworkSpeed", String::class.java, String::class.java)
        check(method.returnType == Void.TYPE)
        hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? View
            if (view != null && Looper.myLooper() == Looper.getMainLooper() && icons[view]?.icon == Icon.SPEED) {
                latestSpeed = networkSpeedKb(chain.getArg(0) as? String, chain.getArg(1) as? String)
                if (settings?.options?.speedGraph == true && inPhoneStatusBar(view)) {
                    val now = SystemClock.elapsedRealtime()
                    if (speedHistory.lastOrNull()?.let { now - it.at < 100 } == true) speedHistory.removeLast()
                    speedHistory.addLast(SpeedSample(now, latestSpeed ?: 0.0))
                    pruneSpeedHistory(now)
                }
                refreshSpeed()
            }
            result
        }
        meter.declaredMethods.filter { it.name.startsWith("updateLightDarkTint") }.forEach { tint ->
            hook(tint).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val view = chain.thisObject as? View
                val state = if (Looper.myLooper() == Looper.getMainLooper()) icons[view] else null
                if (state?.icon == Icon.SPEED) {
                    state.colors.forEach { (text, tint) -> text.setTextColor(tint) }
                    state.colors.clear()
                }
                val result = chain.proceed()
                if (view != null && state?.icon == Icon.SPEED) {
                    colorSpeed(view, state)
                    updateSpeedMirror()
                }
                result
            }
        }
    }

    private fun refreshSpeed() {
        val current = settings ?: return
        val speed = latestSpeed
        val threshold = current.networkSpeedThreshold
        main.removeCallbacks(speedCheck)
        speedBlocked = if (speed == null || threshold == null) {
            slowSince = null
            false
        } else if (!current.options.speedSmoothing) {
            slowSince = null
            speed <= threshold
        } else {
            val below = speed <= (threshold * 0.6)
            when {
                speed > threshold -> { slowSince = null; false }
                speedBlocked -> true
                !below -> { slowSince = null; false }
                else -> {
                    val now = SystemClock.uptimeMillis()
                    val start = slowSince ?: now.also { slowSince = it }
                    val remaining = 3000 - (now - start)
                    if (remaining > 0) main.postDelayed(speedCheck, remaining)
                    remaining <= 0
                }
            }
        }
        icons.entries.toList().filter { it.value.icon == Icon.SPEED }.forEach { (view, state) ->
            install("Refresh network speed") {
                applyIcon(view, state)
                colorSpeed(view, state)
            }
        }
        install("Network speed beside clock") { updateSpeedMirror() }
    }

    private fun colorSpeed(view: View, state: IconState) {
        val speed = latestSpeed
        val color = if (settings?.options?.speedColors == true && speed != null) when {
            speed >= 1024 -> 0xFF00C853.toInt()
            speed >= 50 -> 0xFF26894E.toInt()
            else -> 0xFF708778.toInt()
        } else null
        val fields = speedFields ?: return
        for (field in listOf(fields.first, fields.second)) {
            val text = field.get(view) as? TextView ?: continue
            if (color == null) state.colors.remove(text)?.let(text::setTextColor)
            else {
                state.colors.putIfAbsent(text, text.textColors)
                if (text.currentTextColor != color) text.setTextColor(color)
            }
        }
    }

    private fun updateSpeedMirror() {
        val current = settings ?: return
        val clock = clockControllers.keys.firstOrNull { it.isAttachedToWindow }
        val parent = clock?.parent as? LinearLayout
        val source = icons.entries.firstOrNull { it.value.icon == Icon.SPEED && it.key.isAttachedToWindow }
        val old = speedMirror?.get()
        if (!current.options.speedByClock || profile != null || clock == null || parent == null || source == null) {
            (old?.parent as? ViewGroup)?.removeView(old)
            speedMirror = null
            source?.let { applyIcon(it.key, it.value) }
            return
        }
        val fields = speedFields ?: return
        val number = fields.first.get(source.key) as? TextView ?: return
        val unit = fields.second.get(source.key) as? TextView ?: return
        val mirror = if (old?.parent === parent) checkNotNull(old) else {
            (old?.parent as? ViewGroup)?.removeView(old)
            TextView(clock.context).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setSingleLine()
                setPadding((4 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                parent.addView(this, parent.indexOfChild(clock) + 1, LinearLayout.LayoutParams(-2, -1))
                speedMirror = WeakReference(this)
            }
        }
        mirror.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, number.textSize)
        mirror.setTextColor(number.currentTextColor)
        mirror.text = "${number.text} ${unit.text}"
        mirror.visibility = if (speedBlocked || source.value.visibility != View.VISIBLE) View.GONE else View.VISIBLE
        applyIcon(source.key, source.value)
    }

    private fun hookBatteryMeter(loader: ClassLoader) {
        val meter = Class.forName(
            "com.android.systemui.statusbar.views.MiuiBatteryMeterView", false, loader,
        ).asSubclass(View::class.java)
        for (target in meter.declaredConstructors.filterNot { it.isSynthetic }) {
            install("Battery constructor") {
                hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view ->
                        trackIcon(view, Icon.BATTERY)
                        view.post { install("Refresh attached battery") { icons[view]?.let { applyIcon(view, it) } } }
                    }
                    result
                }
            }
        }
    }

    private fun trackIcon(view: View, icon: Icon) {
        val state = icons.getOrPut(view) { IconState(icon, view.visibility) }
        applyIcon(view, state)
    }

    private fun applyIcon(view: View, state: IconState) {
        val current = settings ?: return
        val hide = when {
            profile == Profile.READING && inPhoneStatusBar(view) -> state.icon != Icon.CLOCK
            profile == Profile.GAMING && inPhoneStatusBar(view) -> state.icon != Icon.SPEED
            else -> when (state.icon) {
                Icon.MOBILE -> current.hideMobile
                Icon.WIFI -> current.hideWifi
                Icon.NO_SIM -> current.hideNoSim
                Icon.BATTERY -> current.hideBattery
                Icon.CLOCK, Icon.OTHER -> false
                Icon.SPEED -> speedBlocked || speedMirror?.get()?.isAttachedToWindow == true
            }
        }
        if (hide == state.hidden) return
        if (hide) state.visibility = view.visibility
        val desired = if (hide) View.GONE else state.visibility
        state.hidden = hide
        applyingVisibility = true
        try {
            view.visibility = desired
            view.requestLayout()
            (view.parent as? View)?.requestLayout()
        } finally {
            applyingVisibility = false
        }
    }

    private fun prepareForegroundReader(loader: ClassLoader) {
        val wrapper = Class.forName("com.android.systemui.shared.system.ActivityManagerWrapper", false, loader)
        val instance = wrapper.getField("sInstance").get(null)
        val running = wrapper.getMethod("getRunningTask")
        val topActivity = running.returnType.getField("topActivity")
        foregroundReader = {
            val task = running.invoke(instance)
            val component = task?.let { topActivity.get(it) as? android.content.ComponentName }
            component?.packageName
        }
    }

    private fun refreshForeground(refresh: Boolean = true) {
        val host = lineHosts.keys.firstOrNull { it.isAttachedToWindow }
        val options = settings?.options
        val scopedClock = options?.secondClock == true && options.secondClockApps.isNotBlank()
        val enabled = (readingPackages.isNotEmpty() || gamingPackages.isNotEmpty() || scopedClock) && foregroundReader != null
        val app = if (enabled && host?.context?.getSystemService(PowerManager::class.java)?.isInteractive == true) {
            try {
                foregroundReader?.invoke()
            } catch (error: ReflectiveOperationException) {
                report(Log.WARN, "Foreground app detection unavailable", error)
                foregroundReader = null
                null
            }
        } else null
        foregroundApp = app
        val next = when (app) {
            in gamingPackages -> Profile.GAMING
            in readingPackages -> Profile.READING
            else -> null
        }
        if (profile != next) {
            profile = next
            if (refresh) {
                refreshSpeed()
                refreshViews()
            }
        }
        if (enabled && foregroundReader != null) main.postDelayed(foregroundCheck, 1000)
    }

    private fun pruneSpeedHistory(now: Long) {
        while (speedHistory.firstOrNull()?.let { now - it.at > 10_000 } == true) speedHistory.removeFirst()
    }

    private fun refreshExtras() {
        main.removeCallbacks(extrasCheck)
        val options = settings?.options ?: return
        val hostList = lineHosts.keys.filter { it.isAttachedToWindow }
        val uiEnabled = options.speedGraph || options.headphoneTrack || options.secondClock || options.privacyLabels
        if (uiEnabled || options.timerLine) main.postDelayed(extrasCheck, 1000)
        val interactive = hostList.firstOrNull()?.context?.getSystemService(PowerManager::class.java)?.isInteractive == true
        timer = null
        if (options.timerLine && interactive && profile == null) {
            install("Read system timer") { timer = timerReader?.invoke() }
        }
        lineHosts.values.forEach { it?.drawable?.get()?.timer = timer }
        if (!uiEnabled || !interactive || (profile != null && !options.privacyLabels)) {
            extras.values.forEach { it.get()?.clear() }
            return
        }
        val uiOptions = if (profile == null) options else StatusBarOptions(privacyLabels = options.privacyLabels)
        pruneSpeedHistory(SystemClock.elapsedRealtime())
        var privacyKinds = emptySet<String>()
        if (uiOptions.privacyLabels) install("Read privacy indicators") {
            privacyKinds = privacyReader?.invoke().orEmpty()
        }
        val clockApps = packageNames(uiOptions.secondClockApps)
        for (host in hostList) install("Status bar extras") {
            val controller = extras[host]?.get() ?: StatusBarExtras(host).also {
                extras[host] = WeakReference(it)
                host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) = it.clear()
                })
            }
            val clockId = host.resources.getIdentifier("clock", "id", SYSTEM_UI)
            val clock = host.findViewById<View>(clockId) as? TextView ?: clockControllers.keys.firstOrNull {
                it.isShown && generateSequence<View>(it) { view -> view.parent as? View }.any { it === host }
            }
            val speedView = speedMirror?.get()?.takeIf { it.isShown }
                ?: icons.entries.firstOrNull { (view, state) ->
                    state.icon == Icon.SPEED && view.isShown &&
                        generateSequence(view) { it.parent as? View }.any { it === host }
                }?.key
            controller.update(
                uiOptions, clock, speedView,
                clockApps.isEmpty() || foregroundApp in clockApps, speedHistory.toList(), !speedBlocked && speedView != null,
                privacyKinds, weatherTint?.defaultColor ?: clock?.currentTextColor ?: -1,
            )
        }
    }

    private fun prepareTimerReader(loader: ClassLoader) {
        val prefix = "com.android.systemui.statusbar.notification.collection."
        val collection = Class.forName(prefix + "NotifCollection", false, loader)
        val entry = Class.forName(prefix + "NotificationEntry", false, loader)
        val entries = collection.getDeclaredField("mReadOnlyNotificationSet").apply { isAccessible = true }
        val sbn = entry.getDeclaredField("mSbn").apply { isAccessible = true }
        for (constructor in collection.declaredConstructors) {
            hook(constructor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                val instance = chain.thisObject
                if (instance != null) {
                    if (settings?.debug == true) report(Log.DEBUG, "System timer source attached")
                    timerReader = {
                        val now = SystemClock.elapsedRealtime()
                        val wall = System.currentTimeMillis()
                        (entries.get(instance) as? Collection<*>).orEmpty().asSequence()
                            .mapNotNull { sbn.get(it) as? StatusBarNotification }
                            .filter { it.packageName == "com.android.deskclock" }
                            .mapNotNull { record ->
                                val notification = record.notification
                                val isTimer = notification.channelId == "channel_id_deskclock_timer" ||
                                    notification.category == Notification.CATEGORY_STOPWATCH
                                val countdown = notification.extras?.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) == true &&
                                    notification.extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == true
                                val remaining = notification.`when` - wall
                                val duration = notification.`when` - record.postTime
                                if (isTimer && countdown && remaining > 0 && duration > 0) {
                                    TimerProgress(now + remaining, duration)
                                } else null
                            }.minByOrNull { it.end }
                    }
                }
                result
            }
        }
    }

    private fun preparePrivacyReader(loader: ClassLoader) {
        val prefix = "com.android.systemui.privacy."
        val controller = Class.forName(prefix + "PrivacyItemController", false, loader)
        val item = Class.forName(prefix + "PrivacyItem", false, loader)
        val list = controller.getDeclaredField("privacyList").apply { isAccessible = true }
        val type = item.getDeclaredField("privacyType").apply { isAccessible = true }
        val paused = item.getDeclaredField("paused").apply { isAccessible = true }
        for (constructor in controller.declaredConstructors) {
            hook(constructor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                val instance = chain.thisObject
                if (instance != null) {
                    if (settings?.debug == true) report(Log.DEBUG, "Privacy source attached")
                    privacyReader = {
                        val snapshot = synchronized(instance) { (list.get(instance) as? List<*>).orEmpty().toList() }
                        snapshot.mapNotNull { reading ->
                            if (reading == null || paused.getBoolean(reading)) null else (type.get(reading) as? Enum<*>)?.name
                        }.toSet()
                    }
                }
                result
            }
        }
    }

    private fun hookStatusBarGestures() {
        val target = ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val host = chain.thisObject as? View
            val event = chain.getArg(0) as? MotionEvent
            if (host != null && event != null && host in lineHosts &&
                (settings?.options?.clockGestures == true || weatherViews[host]?.get() != null)
            ) {
                val detector = statusBarGestures.getOrPut(host) { createStatusBarGestureDetector(host) }
                detector.onTouchEvent(event)
            }
            chain.proceed()
        }
    }

    private fun createStatusBarGestureDetector(host: View): GestureDetector {
        val hostRef = WeakReference(host)
        val listener = object : GestureDetector.SimpleOnGestureListener() {
            private fun clockAt(event: MotionEvent): TextView? {
                val targetHost = hostRef.get()?.takeIf { it.isAttachedToWindow } ?: return null
                if (settings?.options?.clockGestures != true) return null
                return clockControllers.keys.firstOrNull { clock ->
                    if (!clock.isShown || !clock.isAttachedToWindow ||
                        generateSequence(clock as View) { it.parent as? View }.none { it === targetHost }
                    ) return@firstOrNull false

                    containsTouch(clock, event)
                }
            }

            private fun weatherAt(event: MotionEvent): StatusBarWeather? {
                val targetHost = hostRef.get()?.takeIf { it.isAttachedToWindow } ?: return null
                return weatherViews[targetHost]?.get()?.takeIf { containsTouch(it.view, event) }
            }

            override fun onDown(event: MotionEvent): Boolean = weatherAt(event) != null || clockAt(event) != null

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (weatherAt(event) != null) return false
                val clock = clockAt(event) ?: return false
                val current = settings ?: return false
                val controller = clockControllers[clock]?.get() ?: return false
                secondsOverride = !(secondsOverride ?: current.clockSeconds)
                install("Toggle clock seconds") { updateClockDisplay(controller) }
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                val weather = weatherAt(event)
                if (weather != null) {
                    install("Open Xiaomi Weather") { weather.openApp() }
                    return
                }
                val clock = clockAt(event) ?: return
                install("Open alarms") {
                    val intent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    clock.context.startActivity(intent)
                }
            }

        }
        return GestureDetector(host.context, listener, main).apply {
            // SimpleOnGestureListener also implements OnDoubleTapListener.
            setOnDoubleTapListener(null)
        }
    }

    private fun containsTouch(view: View, event: MotionEvent): Boolean {
        if (!view.isShown || !view.isAttachedToWindow) return false
        val bounds = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(bounds)) return false
        val rootPosition = IntArray(2)
        view.rootView.getLocationOnScreen(rootPosition)
        bounds.offset(rootPosition[0], rootPosition[1])
        return bounds.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    // Limit the alternate child order to measurement and layout calls. OEM icon indices stay intact.
    private data class LayoutChildren(val group: ViewGroup, val children: List<View>)
    private var layoutChildren: LayoutChildren? = null
    private val notificationVisibility = WeakHashMap<View, Int>()

    private fun hookStatusBarLayout(loader: ClassLoader) {
        val displayable = Class.forName("com.android.systemui.statusbar.StatusIconDisplayable", false, loader)
        val slot = displayable.getMethod("getSlot")
        val iconView = Class.forName("com.android.systemui.statusbar.StatusBarIconView", false, loader)
        val notification = iconView.getMethod("getNotification")
        val status = Class.forName("com.android.systemui.statusbar.views.MiuiStatusIconContainer", false, loader)
        val notices = Class.forName("com.android.systemui.statusbar.phone.NotificationIconContainer", false, loader)
        val spacing = status.getDeclaredField("iconSpacing").apply { isAccessible = true }
        val count = ViewGroup::class.java.getDeclaredMethod("getChildCount")
        val child = ViewGroup::class.java.getDeclaredMethod("getChildAt", Int::class.javaPrimitiveType)
        hook(count).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val layout = layoutChildren
            if (layout != null && layout.group === chain.thisObject && Looper.myLooper() == Looper.getMainLooper()) {
                layout.children.size
            } else chain.proceed()
        }
        hook(child).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val layout = layoutChildren
            if (layout != null && layout.group === chain.thisObject && Looper.myLooper() == Looper.getMainLooper()) {
                layout.children.getOrNull(chain.getArg(0) as Int)
            } else chain.proceed()
        }
        for (owner in listOf(status, notices)) {
            owner.declaredMethods.filter { it.name in setOf("onMeasure", "onLayout", "calculateIconXTranslations") }
                .forEach { target ->
                    hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                        val group = chain.thisObject as? ViewGroup
                        val current = settings?.options
                        if (group == null || current == null || !inPhoneStatusBar(group) ||
                            Looper.myLooper() != Looper.getMainLooper() || layoutChildren?.group === group
                        ) return@intercept chain.proceed()

                        if (owner == notices && allowedPackages == null && profile == null &&
                            notificationVisibility.isEmpty()
                        ) return@intercept chain.proceed()
                        if (owner == status && current.iconOrder.isEmpty() && current.iconSpacing == null) {
                            return@intercept chain.proceed()
                        }
                        val original = List(group.childCount, group::getChildAt)
                        val children = if (owner == notices) {
                            val selected = original.filter { view ->
                                profile == null && (allowedPackages == null ||
                                    (iconView.isInstance(view) &&
                                        (notification.invoke(view) as? StatusBarNotification)?.packageName in allowedPackages.orEmpty()))
                            }
                            if (target.name == "onMeasure") {
                                for (view in original) {
                                    if (view in selected) {
                                        notificationVisibility.remove(view)?.let { view.visibility = it }
                                    } else {
                                        notificationVisibility.putIfAbsent(view, view.visibility)
                                        if (view.visibility != View.GONE) view.visibility = View.GONE
                                    }
                                }
                            }
                            selected
                        } else {
                            val order = current.iconOrder.split(',').filter(String::isNotEmpty)
                            val ranks = order.withIndex().associate { it.value to it.index }
                            val ranked = original.mapNotNull { view ->
                                if (!displayable.isInstance(view)) return@mapNotNull null
                                val key = slot.invoke(view) as? String
                                ranks[key]?.let { view to it }
                            }
                            val rankedViews = ranked.map { it.first }.toSet()
                            val sorted = ranked.sortedBy { it.second }.map { it.first }.iterator()
                            original.map { if (it in rankedViews) sorted.next() else it }
                        }
                        val old = layoutChildren
                        val gap = current.iconSpacing?.takeIf { owner == status }
                        val oldGap = gap?.let { spacing.getInt(group) }
                        if (gap != null) spacing.setInt(group, (gap * group.resources.displayMetrics.density + 0.5f).toInt())
                        layoutChildren = LayoutChildren(group, children)
                        try {
                            chain.proceed()
                        } finally {
                            layoutChildren = old
                            if (oldGap != null) spacing.setInt(group, oldGap)
                        }
                    }
                }
        }

        // Xiaomi lays the battery and the other icons out in separate sibling views.
        val batteryGroup = Class.forName("com.android.systemui.statusbar.views.MiuiStatusBatteryContainer", false, loader)
        val layout = batteryGroup.getDeclaredMethod(
            "onLayout", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        hook(layout).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            val group = chain.thisObject as? ViewGroup
            val order = settings?.options?.iconOrder.orEmpty().split(',').filter(String::isNotEmpty)
            if (group != null && inPhoneStatusBar(group) && order.size > 1 &&
                "battery" in order.dropLast(1)
            ) {
                val battery = (0 until group.childCount).map(group::getChildAt)
                    .firstOrNull { icons[it]?.icon == Icon.BATTERY && it.visibility == View.VISIBLE }
                val tray = (0 until group.childCount).map(group::getChildAt).firstOrNull(status::isInstance)
                // Move across the icon tray only when the two views are adjacent; leave privacy indicators alone.
                if (battery != null && tray != null && tray.width > 0 && battery.width > 0) {
                    if (group.layoutDirection == View.LAYOUT_DIRECTION_RTL && battery.right == tray.left) {
                        battery.offsetLeftAndRight(tray.width)
                        tray.offsetLeftAndRight(-battery.width)
                    } else if (group.layoutDirection != View.LAYOUT_DIRECTION_RTL && tray.right == battery.left) {
                        battery.offsetLeftAndRight(-tray.width)
                        tray.offsetLeftAndRight(battery.width)
                    }
                }
            }
            result
        }
    }

    private fun inPhoneStatusBar(view: View): Boolean =
        generateSequence(view) { it.parent as? View }.any { it in lineHosts }

    private inline fun install(feature: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            report(Log.WARN, "$feature path unavailable. Skipping.", error)
        } catch (error: LinkageError) {
            report(Log.WARN, "$feature path incompatible. Skipping.", error)
        }
    }

    private fun report(level: Int, message: String, error: Throwable? = null) {
        try {
            if (error == null) log(level, LOG_TAG, message) else log(level, LOG_TAG, message, error)
        } catch (_: Exception) {
            Log.println(level, LOG_TAG, message)
        } catch (_: LinkageError) {
            Log.println(level, LOG_TAG, message)
        }
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
