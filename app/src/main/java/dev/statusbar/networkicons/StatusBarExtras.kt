package dev.statusbar.networkicons

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.roundToInt

internal data class SpeedSample(val at: Long, val kb: Double)

internal data class TimerProgress(val end: Long, val duration: Long)

internal class StatusBarExtras(private val host: View) {
    private val density = host.resources.displayMetrics.density
    private val second = label(11f)
    private val track = label(11f).apply { maxWidth = (92 * density).roundToInt() }
    private val privacy = label(10f)
    private val graph = SpeedGraph(host.context)
    private var lastTrack: String? = null
    private var trackUntil = 0L
    @Volatile private var observedTrack: String? = null
    private var readingTrack = false
    private var trackRequest = 0
    private var mediaFailed = false
    private var lastZone: String? = null
    private var zoneId: ZoneId? = null
    private val chinese get() = host.resources.configuration.locales[0].language == "zh"

    fun update(
        options: StatusBarOptions,
        clock: TextView?,
        speedView: View?,
        showSecond: Boolean,
        samples: List<SpeedSample>,
        speedVisible: Boolean,
        privacyKinds: Set<String>,
        tint: Int,
    ) {
        val parent = clock?.parent as? LinearLayout ?: find<LinearLayout>("phone_status_bar_left_container")
        val speedGroup = if (speedView is TextView) parent else find<LinearLayout>("system_icons")
        if (options.speedGraph && speedVisible && samples.any { it.kb > 0 } && speedGroup != null) {
            val index = if (speedView?.parent === speedGroup) speedGroup.indexOfChild(speedView) + 1 else 0
            place(graph, speedGroup, index, (27 * density).roundToInt())
            graph.update(samples, tint)
        } else detach(graph)

        var index = if (parent == null) 0 else parent.indexOfChild(clock) + 1
        if (graph.parent === parent && graph.parent != null) index = parent!!.indexOfChild(graph) + 1
        if (options.secondClock && showSecond && parent != null) {
            if (lastZone != options.secondClockZone) {
                lastZone = options.secondClockZone
                zoneId = runCatching { ZoneId.of(options.secondClockZone) }.getOrNull()
            }
            val time = zoneId?.let { ZonedDateTime.now(it).format(clockFormat) }
            setLabel(second, time, tint, parent, index++)
            second.contentDescription = time?.let { "${options.secondClockZone} $it" }
        } else detach(second)

        val title = if (options.headphoneTrack && parent != null &&
            host.context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true
        ) readTrack() else {
            observedTrack = null
            trackRequest++
            null
        }
        val now = SystemClock.elapsedRealtime()
        if (title != lastTrack) {
            lastTrack = title
            trackUntil = now + 4000
        }
        setLabel(track, title?.takeIf { now < trackUntil }, tint, parent, index)

        val indicator = find<View>("home_privacy_container")?.takeIf { it.isShown }
        val privacyGroup = (indicator?.parent as? LinearLayout) ?: find<LinearLayout>("privacy_container")
        val label = privacyKinds.mapNotNull {
            when (it) {
                "TYPE_CAMERA" -> if (chinese) "摄像头" else "Camera"
                "TYPE_MICROPHONE" -> if (chinese) "麦克风" else "Mic"
                else -> null
            }
        }.joinToString(" · ").ifEmpty { null }
        val privacyIndex = if (indicator != null && privacyGroup != null && indicator.parent === privacyGroup) {
            val position = privacyGroup.indexOfChild(indicator)
            val existing = privacyGroup.indexOfChild(privacy)
            if (existing in 0 until position) position - 1 else position
        } else 0
        setLabel(privacy, label?.takeIf { options.privacyLabels }, tint, privacyGroup, privacyIndex)
    }

    fun clear() {
        listOf(second, track, privacy, graph).forEach(::detach)
        lastTrack = null
        trackUntil = 0
        observedTrack = null
        trackRequest++
    }

    private fun readTrack(): String? {
        if (!readingTrack && !mediaFailed) {
            readingTrack = true
            val request = ++trackRequest
            val context = host.context
            mediaReader.execute {
                val title = try {
                    currentTrack(context)
                } catch (error: RuntimeException) {
                    main.post { mediaFailed = true }
                    Log.w(LOG_TAG, "Media playback route unavailable", error)
                    null
                }
                main.post {
                    readingTrack = false
                    if (request == trackRequest) observedTrack = title
                }
            }
        }
        return observedTrack
    }

    private fun currentTrack(context: Context): String? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        if (!isHeadphoneRoute(audio)) return null
        val sessions = context.getSystemService(MediaSessionManager::class.java) ?: return null
        return sessions.getActiveSessions(null).firstNotNullOfOrNull { controller ->
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return@firstNotNullOfOrNull null
            controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("InlinedApi")
    private fun isHeadphoneRoute(audio: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < 33) return audio.isWiredHeadsetOn || audio.isBluetoothA2dpOn
        val devices = audio.getAudioDevicesForAttributes(mediaAttributes)
        val headphoneTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
        )
        return devices.isNotEmpty() && devices.all { it.type in headphoneTypes }
    }

    private inline fun <reified T : View> find(name: String): T? {
        val id = host.resources.getIdentifier(name, "id", host.context.packageName)
        return host.findViewById<View>(id) as? T
    }

    private fun label(size: Float) = TextView(host.context).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = size
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        setPaddingRelative((4 * density).roundToInt(), 0, (2 * density).roundToInt(), 0)
    }

    private fun setLabel(
        label: TextView, value: String?, tint: Int, parent: LinearLayout?, index: Int,
    ) {
        if (value == null || parent == null) {
            detach(label)
            return
        }
        if (label.text != value) label.text = value
        if (label.currentTextColor != tint) label.setTextColor(tint)
        place(label, parent, index, -2)
    }

    private fun place(view: View, parent: LinearLayout, index: Int, width: Int) {
        if (view.parent === parent && parent.indexOfChild(view) == index) return
        detach(view)
        parent.addView(view, index.coerceIn(0, parent.childCount), LinearLayout.LayoutParams(width, -1))
    }

    private fun detach(view: View) {
        (view.parent as? LinearLayout)?.removeView(view)
    }

    private class SpeedGraph(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var samples = emptyList<SpeedSample>()

        fun update(next: List<SpeedSample>, tint: Int) {
            samples = next
            paint.color = tint
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val peak = samples.maxByOrNull { it.kb } ?: return
            if (peak.kb <= 0) return
            val now = SystemClock.elapsedRealtime()
            val left = 3 * resources.displayMetrics.density
            val right = width - left
            val yBottom = height * 0.66f
            val yRange = height * 0.32f
            fun x(sample: SpeedSample) = right - (right - left) * ((now - sample.at) / 10000f).coerceIn(0f, 1f)
            fun y(sample: SpeedSample) = yBottom - yRange * (sample.kb / peak.kb).toFloat()
            path.rewind()
            samples.forEachIndexed { index, sample ->
                if (index == 0) path.moveTo(x(sample), y(sample)) else path.lineTo(x(sample), y(sample))
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x(peak), y(peak), 1.5f * paint.strokeWidth, paint)
        }
    }

    private companion object {
        val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val mediaAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val mediaReader = Executors.newSingleThreadExecutor()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
