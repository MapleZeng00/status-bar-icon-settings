package dev.statusbar.networkicons

import android.content.SharedPreferences
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
    private val lineHosts = WeakHashMap<View, WeakReference<BatteryLine>?>()
    private val icons = WeakHashMap<View, IconState>()
    private var applyingVisibility = false

    private enum class Icon { MOBILE, WIFI, NO_SIM, BATTERY }

    private data class IconState(
        val icon: Icon,
        var visibility: Int,
        var hidden: Boolean = false,
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
            install("Battery icon") { hookBatteryMeter(param.classLoader) }
        }
        install("Battery line") { hookBatteryLine(param.classLoader) }
        install("Status bar clock") { hookStatusBarClock(param.classLoader) }
        main.post(reload)
    }

    private fun reloadSettings() {
        val next = IconSettings.read(prefs ?: return)
        val previous = settings
        settings = next
        if (previous != next) report(Log.INFO, "Live settings received")
        if (next.debug) report(Log.DEBUG, "Settings: $next")
        icons.keys.toList().forEach { view ->
            install("Refresh icon") { applyIcon(view, icons.getValue(view)) }
        }
        if (previous?.batteryLine != next.batteryLine ||
            previous.batteryLineColor != next.batteryLineColor ||
            previous.chargingAnimation != next.chargingAnimation
        ) {
            lineHosts.keys.toList().forEach { view ->
                install("Refresh battery line") { updateLine(view, next) }
            }
        }
        if (previous?.clockSeconds != next.clockSeconds || previous.clockColor != next.clockColor) {
            clockControllers.keys.toList().forEach { view ->
                install("Refresh status bar clock") {
                    clockControllers[view]?.get()?.configure(next.clockSeconds, next.clockColor)
                    clockUpdate?.invoke(view)
                }
            }
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
                    settings?.let { controller?.configure(it.clockSeconds, it.clockColor) }
                    controller?.refresh()
                    result
                }
                if (settings?.debug == true) report(Log.DEBUG, "Status bar clock signature: $method")
            }
        }
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
                        settings?.let { updateLine(view, it) }
                    }
                    result
                }
                if (settings?.debug == true) report(Log.DEBUG, "Battery line signature: $method")
            }
        }
    }

    private fun updateLine(view: View, settings: IconSettings) {
        lineHosts[view]?.get()?.uninstall()
        lineHosts[view] = if (settings.batteryLine) {
            val line = BatteryLine(view, settings.batteryLineColor, settings.chargingAnimation)
            line.install()
            WeakReference(line)
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
                    else -> null
                }
                if (icon != null) trackIcon(view, icon)
            }
            result
        }
    }

    private fun hookNetworkIconMeasurement(loader: ClassLoader) {
        // MIUI measures icons using StatusIconDisplayable.isIconVisible(), even for GONE views.
        val classes = listOf(
            "com.android.systemui.statusbar.StatusBarIconView",
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

    private fun hookBatteryMeter(loader: ClassLoader) {
        val meter = Class.forName(
            "com.android.systemui.statusbar.views.MiuiBatteryMeterView", false, loader,
        ).asSubclass(View::class.java)
        for (target in meter.declaredConstructors.filterNot { it.isSynthetic }) {
            install("Battery constructor") {
                hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { trackIcon(it, Icon.BATTERY) }
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
        val hide = when (state.icon) {
            Icon.MOBILE -> current.hideMobile
            Icon.WIFI -> current.hideWifi
            Icon.NO_SIM -> current.hideNoSim
            Icon.BATTERY -> current.hideBattery
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
