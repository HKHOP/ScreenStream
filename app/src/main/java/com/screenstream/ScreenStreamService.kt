package com.screenstream

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.pedro.rtspserver.RtspServerDisplay
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class ScreenStreamService : Service(), ConnectCheckerRtsp {

    companion object {
        const val ACTION_START = "com.screenstream.action.START"
        const val ACTION_STOP = "com.screenstream.action.STOP"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_PROJECTION_DATA = "EXTRA_PROJECTION_DATA"

        const val BROADCAST_STATUS = "com.screenstream.BROADCAST_STATUS"
        const val EXTRA_CLIENT_COUNT = "CLIENT_COUNT"
        const val EXTRA_IS_STREAMING = "IS_STREAMING"

        const val PORT = 8080

        private const val TAG = "ScreenStreamService"
        private const val SCALE = 0.6f
        private const val FPS = 30
        private const val FRAME_MS = 1000 / FPS
        private const val QUALITY = 75

        private const val NOTIF_ID = 101
        private const val CHANNEL_ID = "screen_stream"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var httpServer: HttpMjpegServer? = null
    private var rtspServer: RtspServerDisplay? = null
    private var rtspMode = false

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

        createNotification()

        handlerThread = HandlerThread("screen_capture").apply { start() }
        handler = Handler(handlerThread!!.looper)

        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = (metrics.widthPixels * SCALE).toInt()
        val height = (metrics.heightPixels * SCALE).toInt()
        val density = metrics.densityDpi

        if (rtspMode) {
            startRtsp(width, height, density, resultCode, data)
        } else {
            startHttp(width, height, density, resultCode, data)
        }

        broadcast(0)
    }

    // ---------------- HTTP MODE ----------------

    private fun startHttp(w: Int, h: Int, density: Int, code: Int, data: Intent) {

        httpServer = HttpMjpegServer(PORT) { broadcast(it) }
        httpServer?.start()

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            if (now - lastFrame < FRAME_MS) {
                img.close()
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

                        frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, bufferStream)

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

    // ---------------- RTSP MODE ----------------

    private fun startRtsp(
        w: Int,
        h: Int,
        density: Int,
        code: Int,
        data: Intent
    ) {
        val port = 1935

        rtspServer = RtspServerDisplay(this, true, this, port)
        rtspServer?.setVideoBitrateOnFly(2_500_000)

        if (rtspServer?.prepareAudio() == true &&
            rtspServer?.prepareVideo(w, h, FPS, 2_500_000, 0, density) == true
        ) {
            rtspServer?.startStream(code, data)
        } else {
            stopSelf()
        }
    }

    // ---------------- BROADCAST ----------------

    private fun broadcast(count: Int) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_CLIENT_COUNT, count)
            putExtra(EXTRA_IS_STREAMING, true)
            setPackage(packageName)
        })
    }

    // ---------------- STOP ----------------

    private fun stopStream() {

        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()

        httpServer?.stop()

        if (rtspServer?.isStreaming == true) {
            rtspServer?.stopStream()
        }

        handlerThread?.quitSafely()
        executor.shutdownNow()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_CLIENT_COUNT, 0)
            putExtra(EXTRA_IS_STREAMING, false)
            setPackage(packageName)
        })
    }

    private fun createNotification() {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Stream",
            NotificationManager.IMPORTANCE_LOW
        )

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

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

    // ---------------- RTSP CALLBACKS ----------------

    override fun onConnectionSuccessRtsp() { broadcast(1) }
    override fun onConnectionFailedRtsp(reason: String) = Log.e(TAG, reason)
    override fun onDisconnectRtsp() = broadcast(0)
    fun onAuthErrorRtsp() = Log.e(TAG, "auth error")
    fun onAuthSuccessRtsp() = Log.i(TAG, "auth ok")
    fun onNewBitrateRtsp(bitrate: Long) {}
}
