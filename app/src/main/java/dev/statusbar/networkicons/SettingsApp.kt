package dev.statusbar.networkicons

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors

class SettingsApp : Application(), XposedServiceHelper.OnServiceListener {
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<(XposedService?) -> Unit>()
    internal val io = Executors.newSingleThreadExecutor()
    internal var service: XposedService? = null
        private set

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        main.post {
            this.service = service
            observers.toList().forEach { it(service) }
        }
    }

    override fun onServiceDied(service: XposedService) {
        main.post {
            if (this.service === service) {
                this.service = null
                observers.toList().forEach { it(null) }
            }
        }
    }

    internal fun observe(observer: (XposedService?) -> Unit) {
        observers.add(observer)
        observer(service)
    }

    internal fun removeObserver(observer: (XposedService?) -> Unit) {
        observers.remove(observer)
    }
}
