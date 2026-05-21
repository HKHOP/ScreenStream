
package com.screenstream

import android.app.Activity
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenStreamService
 *
 * Notes about fixes applied:
 * - Removed compile-time dependency on the external RTSP classes so the file compiles even when
 *   the RTSP library is not present in the classpath (CI/build machines).
 * - RTSP functionality is guarded: if the RTSP library is missing, the service logs and stops.
 * - Kept HTTP MJPEG path intact (uses HttpMjpegServer from your project).
 * - Fixed missing imports (IBinder, WindowManager, etc.) and handled nullable system services safely.
 * - Kept deprecated defaultDisplay usage behind a suppression to preserve compatibility.
 *
 * If you want RTSP to work in CI/builds, add the RTSP library dependency to your Gradle files.
 */
class ScreenStreamService : Service() {

    companion object {
        const val ACTION_START = "com.screenstream.action.START"
        const val ACTION_STOP = "com.screenstream.action.STOP"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_PROJECTION_DATA = "EXTRA_PROJECTION_DATA"

        const val BROADCAST_STATUS = "com.screenstream.BROADCAST_STATUS"
        const val EXTRA_CLIENT_COUNT = "CLIENT_COUNT"
        const val EXTRA_IS_STREAMING = "IS_STREAMING"
        const val EXTRA_RTSP_MODE = "RTSP_MODE"
        const val EXTRA_AUDIO_ACTIVE = "AUDIO_ACTIVE"
        const val EXTRA_AUDIO_REQUESTED = "AUDIO_REQUESTED"
        private const val PREF_IS_STREAMING = "pref_is_streaming"

        const val PORT = 8080

        private const val TAG = "ScreenStreamService"
        private const val DEFAULT_SCALE = 0.6f
        private const val DEFAULT_FPS = 30
        private const val DEFAULT_FRAME_LATENCY_MS = 33L
        private const val DEFAULT_QUALITY = 75

        private const val NOTIF_ID = 101
        private const val CHANNEL_ID = "screen_stream"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Keep RTSP server as Any? so the file compiles without the RTSP library.
    private var httpServer: HttpMjpegServer? = null
    private var rtspEngine: RtspEngine? = null
    private var rtspMode = false
    private var rtspPort = 1935
    private var scale = DEFAULT_SCALE
    private var fps = DEFAULT_FPS
    private var frameIntervalMs = 1000L / DEFAULT_FPS
    private var frameLatencyMs = DEFAULT_FRAME_LATENCY_MS
    private var jpegQuality = DEFAULT_QUALITY

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var bitmap: Bitmap? = null
    private val bufferStream = FastByteArrayOutputStream(512 * 1024)
    private val encodeInProgress = AtomicBoolean(false)

    private var lastFrame = 0L

    private var audioEnabled = false
    private var audioActive = false
    private var audioSource = "mic"
    private var audioBitrateKbps = 128
    private var audioSampleRate = 44100
    private var audioStereo = true
    private var audioRequired = false
    private var audioDisabledByPolicy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

                if (data != null) startStream(code, data)
                else stopSelf()
            }

            ACTION_STOP -> stopStream()
        }

        return START_NOT_STICKY
    }

    private fun startStream(resultCode: Int, data: Intent) {

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        rtspMode = prefs.getBoolean("use_rtsp_mode", false)
        rtspPort = prefs.getString("rtsp_port", "1935")?.toIntOrNull() ?: 1935
        val configuredScale = prefs.getString("stream_scale", "0.6")?.toFloatOrNull()?.coerceIn(0.2f, 1.0f)
            ?: DEFAULT_SCALE
        val configuredFps = prefs.getString("stream_fps", DEFAULT_FPS.toString())?.toIntOrNull()?.coerceIn(5, 60)
            ?: DEFAULT_FPS
        val configuredLatencyMs = prefs.getString("stream_latency_ms", "33")?.toLongOrNull()?.coerceIn(0L, 1000L)
            ?: DEFAULT_FRAME_LATENCY_MS
        val configuredJpegQuality = prefs.getString("jpeg_quality", DEFAULT_QUALITY.toString())?.toIntOrNull()?.coerceIn(10, 100)
            ?: DEFAULT_QUALITY

        scale = configuredScale
        fps = configuredFps
        frameIntervalMs = (1000L / configuredFps).coerceAtLeast(1L)
        frameLatencyMs = configuredLatencyMs
        jpegQuality = configuredJpegQuality
        audioEnabled = prefs.getBoolean("enable_audio", false)
        audioSource = prefs.getString("audio_source", "mic") ?: "mic"
        audioBitrateKbps = prefs.getString("audio_bitrate_kbps", "128")?.toIntOrNull()?.coerceIn(32, 320) ?: 128
        audioSampleRate = prefs.getString("audio_sample_rate", "44100")?.toIntOrNull() ?: 44100
        audioStereo = prefs.getBoolean("audio_stereo", true)
        audioRequired = prefs.getBoolean("audio_required", false)
        audioDisabledByPolicy = false
        audioActive = rtspMode && audioEnabled

        val sourceNeedsMic = audioSource == "mic"
        val sourceIsPlayback = audioSource == "playback"
        val playbackSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val hasRecordAudioPermission =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (audioEnabled && !rtspMode) {
            Log.w(TAG, "Audio requested but MJPEG mode is video-only. Ignoring audio until RTSP mode is enabled.")
            audioActive = false
        }
        if (audioEnabled && rtspMode && sourceIsPlayback && !playbackSupported) {
            Log.w(TAG, "Playback capture requested but unsupported on API ${Build.VERSION.SDK_INT}.")
            if (audioRequired) {
                setStreamingState(false)
                broadcast(0, false)
                stopSelf()
                return
            }
            audioDisabledByPolicy = true
            audioActive = false
        }
        if (audioEnabled && rtspMode && sourceNeedsMic && !hasRecordAudioPermission) {
            Log.w(TAG, "Mic capture requested but RECORD_AUDIO permission denied.")
            if (audioRequired) {
                setStreamingState(false)
                broadcast(0, false)
                stopSelf()
                return
            }
            audioDisabledByPolicy = true
            audioActive = false
        }

        createNotification()

        handlerThread = HandlerThread("screen_capture").apply { start() }
        handler = Handler(handlerThread!!.looper)

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            Log.e(TAG, "WindowManager not available")
            stopSelf()
            return
        }

        // defaultDisplay is deprecated on newer APIs but still widely used; keep compatibility.
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
        val density = metrics.densityDpi

        val started = if (rtspMode) {
            startRtsp(width, height, density, resultCode, data)
        } else {
            startHttp(width, height, density, resultCode, data)
            true
        }

        if (started) {
            // initial broadcast: 0 clients, streaming started
            setStreamingState(true)
            broadcast(0, true)
        }
    }

    // ---------------- HTTP MODE ----------------

    private fun startHttp(w: Int, h: Int, density: Int, code: Int, data: Intent) {

        httpServer = HttpMjpegServer(PORT) { broadcast(it, true) }
        httpServer?.start()

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (pm == null) {
            Log.e(TAG, "MediaProjectionManager not available")
            stopSelf()
            return
        }
        projection = pm.getMediaProjection(code, data)

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection?.createVirtualDisplay(
            "stream",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->

            if (httpServer?.hasClients() != true) {
                val stale = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                stale?.close()
                return@setOnImageAvailableListener
            }

            val now = System.currentTimeMillis()
            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (img == null) return@setOnImageAvailableListener

            val minFrameDelta = maxOf(frameIntervalMs, frameLatencyMs)
            if (now - lastFrame < minFrameDelta) {
                try { img.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }

            lastFrame = now

            try {
                val plane = img.planes[0]
                val buffer = plane.buffer

                val px = plane.pixelStride
                val rs = plane.rowStride
                val padding = rs - px * w

                val bw = w + padding / px

                if (bitmap == null || bitmap!!.width != bw || bitmap!!.height != h) {
                    bitmap = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
                }

                bitmap!!.copyPixelsFromBuffer(buffer)
                img.close()

                val src = bitmap ?: return@setOnImageAvailableListener

                if (!encodeInProgress.compareAndSet(false, true)) {
                    return@setOnImageAvailableListener
                }

                executor.execute {
                    try {
                        synchronized(bufferStream) {

                            bufferStream.reset()

                            val frame = if (padding > 0)
                                Bitmap.createBitmap(src, 0, 0, w, h)
                            else src

                            frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bufferStream)
                        frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bufferStream)

                            httpServer?.broadcastFrame(bufferStream.rawBuffer(), bufferStream.size())

                            if (frame !== src) frame.recycle()
                        }
                    } finally {
                        encodeInProgress.set(false)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "frame error", e)
                try { img.close() } catch (_: Exception) {}
            }

        }, handler)
    }

    // ---------------- RTSP MODE (guarded) ----------------

    private fun startRtsp(
        w: Int,
        h: Int,
        density: Int,
        code: Int,
        data: Intent
    ): Boolean {
        rtspEngine = ReflectiveRtspEngine.createOrNull(this, rtspPort)
        if (rtspEngine == null) {
            Log.w(TAG, "RTSP library not found on classpath; RTSP mode unavailable")
            setStreamingState(false)
            broadcast(0, false)
            stopSelf()
            return false
        }

        val videoPrepared = rtspEngine?.configureVideo(w, h, fps, 2_500_000, 0, density) == true
        if (!videoPrepared) {
            Log.e(TAG, "RTSP prepareVideo failed")
            setStreamingState(false)
            broadcast(0, false)
            stopSelf()
            return false
        }

        val audioPrepared = if (audioEnabled && !audioDisabledByPolicy) {
            val sourceConfigured = rtspEngine?.configureAudioSource(audioSource) == true
            if (!sourceConfigured) {
                Log.w(TAG, "RTSP audio source '$audioSource' is not supported by this RTSP backend.")
                false
            } else {
                rtspEngine?.configureAudio(audioBitrateKbps, audioSampleRate, audioStereo) == true
            }
        } else {
            false
        }

        if (audioEnabled && !audioPrepared) {
            val fallbackAllowed = !audioRequired
            Log.w(TAG, "RTSP audio prepare failed (required=$audioRequired, bitrateKbps=$audioBitrateKbps, sampleRate=$audioSampleRate, stereo=$audioStereo)")
            if (!fallbackAllowed) {
                Log.e(TAG, "Audio is required; aborting RTSP startup")
                setStreamingState(false)
                broadcast(0, false)
                stopSelf()
                return false
            }
            Log.w(TAG, "Audio fallback allowed; continuing with video-only RTSP stream")
        }

        audioActive = audioEnabled && audioPrepared

        val started = rtspEngine?.start(code, data) == true
        if (!started) {
            Log.e(TAG, "RTSP stream start was invoked but server is not streaming")
            setStreamingState(false)
            broadcast(0, false)
            stopSelf()
            return false
        }

        return true
    }

    // ---------------- BROADCAST ----------------

    private fun broadcast(count: Int, isStreaming: Boolean) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_CLIENT_COUNT, count)
            putExtra(EXTRA_IS_STREAMING, isStreaming)
            putExtra(EXTRA_RTSP_MODE, rtspMode)
            putExtra(EXTRA_AUDIO_REQUESTED, audioEnabled)
            putExtra(EXTRA_AUDIO_ACTIVE, isStreaming && audioActive)
            setPackage(packageName)
        })
    }

    // ---------------- STOP ----------------

    private fun stopStream() {

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "virtualDisplay release failed", e)
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "imageReader close failed", e)
        }
        try {
            projection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "projection stop failed", e)
        }

        httpServer?.stop()
        rtspEngine?.stop()

        handlerThread?.quitSafely()
        executor.shutdownNow()

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore on older APIs
        }
        stopSelf()
        setStreamingState(false)

        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_CLIENT_COUNT, 0)
            putExtra(EXTRA_IS_STREAMING, false)
            setPackage(packageName)
        })
    }

    private fun createNotification() {

        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Stream",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming active")
            .setContentText("Screen is being broadcast")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }

    private fun setStreamingState(streaming: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(PREF_IS_STREAMING, streaming)
            .apply()
    }

    private class FastByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun rawBuffer(): ByteArray = buf
    }
}
