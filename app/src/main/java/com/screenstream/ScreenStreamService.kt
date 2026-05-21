package com.hkhop.screenstream

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class ScreenStreamService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"

        // Configurable Parameters
        private const val PORT = 8080
        private const val SCALE = 0.7f // Balanced resolution scale
        private const val JPEG_QUALITY = 70 // High clarity, lower bandwidth
        private const val TARGET_FPS = 30
        private const val FRAME_INTERVAL_MS = 1000 / TARGET_FPS
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "ScreenStreamChannel"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: HttpMjpegServer? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // Reusable objects to prevent continuous GC allocation thrashing
    private var reusableBitmap: Bitmap? = null
    private val jpegCompressionBuffer = ByteArrayOutputStream(1024 * 500) // Pre-allocated 500KB buffer
    private var lastFrameTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    startStreaming(resultCode, resultData)
                }
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, resultData: Intent) {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenStream Active")
            .setContentText("Streaming live on http://localhost:$PORT")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        // Start background thread for processing frames
        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // Start the web server
        server = HttpMjpegServer(PORT)
        server?.start()

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        // Determine adjusted screen dimensions
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * SCALE).toInt()
        val height = (metrics.heightPixels * SCALE).toInt()
        val density = metrics.densityDpi

        // Optimize ImageReader configuration
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenStreamDisplay",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, backgroundHandler
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val currentTime = System.currentTimeMillis()
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            // Frame throttling check
            if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = currentTime

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                // Initialize or reuse preallocated Bitmap structure
                val bitmapWidth = width + rowPadding / pixelStride
                if (reusableBitmap == null || reusableBitmap!!.width != bitmapWidth || reusableBitmap!!.height != height) {
                    reusableBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                }

                reusableBitmap!!.copyPixelsFromBuffer(buffer)
                image.close() // Close immediate hardware frame mapping cleanly

                // Push compression and transmission onto worker thread pool to decouple capture loop
                val processingBitmap = reusableBitmap
                if (processingBitmap != null) {
                    networkExecutor.execute {
                        synchronized(jpegCompressionBuffer) {
                            jpegCompressionBuffer.reset()
                            // Crop out structural row padding if it exists
                            val finalBitmap = if (rowPadding > 0) {
                                Bitmap.createBitmap(processingBitmap, 0, 0, width, height)
                            } else {
                                processingBitmap
                            }
                            
                            if (finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegCompressionBuffer)) {
                                server?.broadcastFrame(jpegCompressionBuffer.toByteArray())
                            }
                            
                            if (rowPadding > 0 && finalBitmap != processingBitmap) {
                                finalBitmap.recycle()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try { image.close() } catch (_: Exception) {}
            }
        }, backgroundHandler)
    }

    private fun stopStreaming() {
        virtualDisplay?.release()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        mediaProjection?.stop()
        server?.stop()
        backgroundThread?.quitSafely()
        networkExecutor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Stream", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
        const val EXTRA_CLIENT_COUNT = "clientCount"

        private const val CHANNEL_ID      = "screenstream_channel"
        private const val NOTIFICATION_ID = 42
        private const val TAG             = "ScreenStreamService"

        /** Scale factor applied to screen resolution before streaming (saves bandwidth/CPU) */
        private const val SCALE = 0.5f
        /** JPEG quality 0-100 */
        private const val JPEG_QUALITY = 65
    }

    // ── State ────────────────────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null

    private val server = HttpMjpegServer(PORT)

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler?      = null

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        server.onClientCountChanged = { count ->
            broadcastStatus(count)
            updateNotification(count)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (data != null && resultCode == Activity.RESULT_OK) {
                    startForeground(NOTIFICATION_ID, buildNotification(0))
                    startCapture(resultCode, data)
                    server.start()
                } else {
                    Log.e(TAG, "Missing projection data — stopping")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown()
    }

    // ── Capture setup ────────────────────────────────────────────────────────
    private fun startCapture(resultCode: Int, data: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)

        // Get real screen dimensions
        val (screenW, screenH, dpi) = getScreenDimensions()
        val captureW = (screenW * SCALE).toInt()
        val captureH = (screenH * SCALE).toInt()

        Log.i(TAG, "Capture size: ${captureW}x${captureH}  dpi=$dpi")

        // ImageReader receives raw RGBA frames from the VirtualDisplay
        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)

        captureThread = HandlerThread("CaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader!!.setOnImageAvailableListener({ reader ->
            processFrame(reader, captureW, captureH)
        }, captureHandler)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenStream",
            captureW, captureH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
        Log.i(TAG, "VirtualDisplay created")
    }

    private fun processFrame(reader: ImageReader, width: Int, height: Int) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane     = image.planes[0]
            val buffer    = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val rowPad    = rowStride - pixStride * width

            // Create bitmap from buffer (may have row padding)
            val bmpWidth = width + rowPad / pixStride
            val raw = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)

            // Crop to exact capture size if padding was added
            val frame = if (bmpWidth != width) {
                val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
                raw.recycle()
                cropped
            } else raw

            // Compress to JPEG and push to streaming server
            val baos = ByteArrayOutputStream(frame.byteCount / 4)
            frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            frame.recycle()

            server.sendFrame(baos.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Frame error: ${e.message}")
        } finally {
            image.close()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private fun shutdown() {
        server.stop()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()

        virtualDisplay  = null
        imageReader     = null
        mediaProjection = null
        captureThread   = null
        captureHandler  = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    data class ScreenInfo(val width: Int, val height: Int, val dpi: Int)

    private fun getScreenDimensions(): ScreenInfo {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            ScreenInfo(bounds.width(), bounds.height(), resources.displayMetrics.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            ScreenInfo(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun broadcastStatus(clientCount: Int) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_CLIENT_COUNT, clientCount)
            `package` = packageName
        })
    }

    // ── Notification ─────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Screen Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Screen stream active" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(clientCount: Int): Notification {
        val stopIntent = Intent(this, ScreenStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val viewerText = if (clientCount == 0) "No viewers connected"
                         else "$clientCount viewer${if (clientCount > 1) "s" else ""} connected"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenStream — Streaming on :$PORT")
            .setContentText(viewerText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(clientCount: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(clientCount))
    }
}
