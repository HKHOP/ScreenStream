
package com.screenstream

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
    private var rtspServer: Any? = null
    private var rtspMode = false
    private var rtspPort = 1935

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var bitmap: Bitmap? = null
    private val bufferStream = ByteArrayOutputStream(512 * 1024)

    private var lastFrame = 0L

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

        if (rtspMode) {
            startRtsp(width, height, density, resultCode, data)
        } else {
            startHttp(width, height, density, resultCode, data)
        }

        // initial broadcast: 0 clients, streaming started
        setStreamingState(true)
        broadcast(0, true)
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

                executor.execute {
                    synchronized(bufferStream) {

                        bufferStream.reset()

                        val frame = if (padding > 0)
                            Bitmap.createBitmap(src, 0, 0, w, h)
                        else src

                        frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bufferStream)

                        httpServer?.broadcastFrame(bufferStream.toByteArray())

                        if (frame !== src) frame.recycle()
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
    ) {
        // The project originally used com.pedro.rtspserver.RtspServerDisplay.
        // To keep this file compilable when that library is not present, we check for the class.
        val classCandidates = listOf(
            "com.pedro.rtspserver.RtspServerDisplay",
            "com.pedro.library.rtsp.rtspserver.RtspServerDisplay"
        )
        val clazz = classCandidates.firstNotNullOfOrNull { className ->
            try {
                Class.forName(className)
            } catch (_: Exception) {
                null
            }
        }
        if (clazz == null) {
            Log.w(TAG, "RTSP library not found on classpath; RTSP mode unavailable")
            stopSelf()
            return
        }

        // If the class exists, attempt to instantiate and call methods reflectively.
        // This keeps compile-time independence while enabling RTSP when the library is present.
        try {
            // Try to find a constructor that accepts (Context, boolean, Object/Listener, int)
            val ctor = clazz.constructors.firstOrNull { it.parameterTypes.isNotEmpty() }
            val instance = if (ctor != null) {
                // Build a parameter array with sensible defaults:
                val params = ctor.parameterTypes.map { paramType ->
                    when {
                        paramType == java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
                        paramType == java.lang.Integer.TYPE -> Integer.valueOf(rtspPort)
                        paramType == java.lang.Integer::class.java -> Integer.valueOf(rtspPort)
                        paramType == java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
                        paramType.isAssignableFrom(Context::class.java) -> this
                        else -> null
                    }
                }.toTypedArray()
                // Some constructors may not accept nulls for listener param; pass null for unknowns.
                ctor.newInstance(*params)
            } else {
                // fallback: try no-arg constructor
                clazz.getDeclaredConstructor().newInstance()
            }
            rtspServer = instance

            try {
                val setPort = clazz.getMethod("setPort", Integer.TYPE)
                setPort.invoke(rtspServer, Integer.valueOf(rtspPort))
            } catch (_: Exception) {
                // ignore if method is absent
            }

            // setVideoBitrateOnFly(int) if available
            try {
                val setBitrate = clazz.getMethod("setVideoBitrateOnFly", Integer.TYPE)
                setBitrate.invoke(rtspServer, Integer.valueOf(2_500_000))
            } catch (_: NoSuchMethodException) { /* ignore if method not present */ }

            // prepareAudio() and prepareVideo(...)
            val audioPrepared = try {
                val m = clazz.getMethod("prepareAudio")
                (m.invoke(rtspServer) as? Boolean) == true
            } catch (e: Exception) {
                Log.w(TAG, "prepareAudio not available or failed", e)
                false
            }

            val videoPrepared = try {
                // try common signature: prepareVideo(width, height, fps, bitrate, rotation, density)
                val m = clazz.getMethod(
                    "prepareVideo",
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE
                )
                (m.invoke(rtspServer, w, h, fps, 2_500_000, 0, density) as? Boolean) == true
            } catch (e: NoSuchMethodException) {
                // try alternative signature: prepareVideo(width, height, fps, bitrate, rotation)
                try {
                    val m2 = clazz.getMethod(
                        "prepareVideo",
                        Integer.TYPE,
                        Integer.TYPE,
                        Integer.TYPE,
                        Integer.TYPE,
                        Integer.TYPE
                    )
                    (m2.invoke(rtspServer, w, h, fps, 2_500_000, 0) as? Boolean) == true
                } catch (ex: Exception) {
                    Log.w(TAG, "prepareVideo not available or failed", ex)
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "prepareVideo invocation failed", e)
                false
            }

            if (videoPrepared) {
                if (!audioPrepared) {
                    Log.w(TAG, "RTSP audio was not prepared; continuing with video-only stream")
                }
                // Try to call startStream(resultCode, intent) or startStream()
                try {
                    val startWithParams = clazz.getMethod("startStream", Integer.TYPE, Intent::class.java)
                    startWithParams.invoke(rtspServer, code, data)
                } catch (e: NoSuchMethodException) {
                    try {
                        val startNoParams = clazz.getMethod("startStream")
                        startNoParams.invoke(rtspServer)
                    } catch (e2: Exception) {
                        Log.e(TAG, "No suitable startStream method found or invocation failed", e2)
                        stopSelf()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start RTSP stream", e)
                    stopSelf()
                }

                // Verify server actually entered streaming state when API supports isStreaming().
                val started = try {
                    val isStreamingMethod = clazz.getMethod("isStreaming")
                    (isStreamingMethod.invoke(rtspServer) as? Boolean) == true
                } catch (_: Exception) {
                    true
                }

                if (!started) {
                    Log.e(TAG, "RTSP stream start was invoked but server is not streaming")
                    setStreamingState(false)
                    broadcast(0, false)
                    stopSelf()
                }
            } else {
                Log.e(TAG, "RTSP prepareVideo failed")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate or use RTSP server reflectively", e)
            stopSelf()
        }
    }

    // ---------------- BROADCAST ----------------

    private fun broadcast(count: Int, isStreaming: Boolean) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_CLIENT_COUNT, count)
            putExtra(EXTRA_IS_STREAMING, isStreaming)
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

        // If RTSP library is present and we created an instance, try to stop and release it reflectively.
        rtspServer?.let { server ->
            try {
                val clazz = server::class.java
                try {
                    val isStreamingMethod = clazz.getMethod("isStreaming")
                    val streaming = (isStreamingMethod.invoke(server) as? Boolean) == true
                    if (streaming) {
                        try {
                            val stopMethod = clazz.getMethod("stopStream")
                            stopMethod.invoke(server)
                        } catch (e: NoSuchMethodException) {
                            Log.w(TAG, "stopStream method not found", e)
                        }
                    }
                } catch (e: NoSuchMethodException) {
                    // ignore
                }

                try {
                    val releaseMethod = clazz.getMethod("release")
                    releaseMethod.invoke(server)
                } catch (e: NoSuchMethodException) {
                    // ignore
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop/release RTSP server reflectively", e)
            }
        }

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
}
