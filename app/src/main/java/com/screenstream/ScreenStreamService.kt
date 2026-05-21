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
import android.util.Log
import android.view.WindowManager
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
        private const val JPEG_QUALITY = 75
        private const val TARGET_FPS = 30
        private const val FRAME_INTERVAL_MS = 1000 / TARGET_FPS
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "ScreenStreamChannel"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    // Server Engines
    private var server: HttpMjpegServer? = null
    private var rtspServerDisplay: RtspServerDisplay? = null
    private var isRtspMode = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var reusableBitmap: Bitmap? = null
    private val jpegCompressionBuffer = ByteArrayOutputStream(1024 * 500)
    private var lastFrameTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (resultData != null) {
                    startStreaming(resultCode, resultData)
                } else {
                    Log.e(TAG, "Projection data was null")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, resultData: Intent) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        isRtspMode = prefs.getBoolean("use_rtsp_mode", false)
        val rtspPort = prefs.getString("rtsp_port", "1935")?.toInt() ?: 1935

        createNotificationChannel()
        val displayPortText = if (isRtspMode) "RTSP: $rtspPort" else "HTTP: $PORT"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Stream Active")
            .setContentText("Streaming live on port $displayPortText")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val width = (metrics.widthPixels * SCALE).toInt()
        val height = (metrics.heightPixels * SCALE).toInt()
        val density = metrics.densityDpi

        if (isRtspMode) {
            // RTSP Pipeline Implementation
            rtspServerDisplay = RtspServerDisplay(this, true, this, rtspPort)
            // Configure base streams parameters matching target video configurations
            rtspServerDisplay?.setVideoBitrateOnFly(2500000) // Default target 2.5 Mbps 
            
            if (rtspServerDisplay?.prepareAudio() == true && rtspServerDisplay?.prepareVideo(width, height, TARGET_FPS, 2500000, 0, density) == true) {
                // Pass intent context data down to inner structures directly
                rtspServerDisplay?.startStream(resultCode, resultData)
                Log.d(TAG, "RTSP Streaming service pipeline activated successfully.")
            } else {
                Log.e(TAG, "Failed configuring hardware codecs for baseline parameters.")
                stopSelf()
                return
            }
        } else {
            // Traditional MJPEG Pipeline Loop fallback path
            server = HttpMjpegServer(PORT) { clientCount ->
                updateStatusBroadcast(clientCount)
            }
            server?.start()

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

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

                    val bitmapWidth = width + rowPadding / pixelStride
                    if (reusableBitmap == null || reusableBitmap!!.width != bitmapWidth || reusableBitmap!!.height != height) {
                        reusableBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                    }

                    reusableBitmap!!.copyPixelsFromBuffer(buffer)
                    image.close()

                    val processingBitmap = reusableBitmap
                    if (processingBitmap != null) {
                        networkExecutor.execute {
                            synchronized(jpegCompressionBuffer) {
                                jpegCompressionBuffer.reset()
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
                    Log.e(TAG, "Error processing frame", e)
                    try { image.close() } catch (_: Exception) {}
                }
            }, backgroundHandler)
        }

        updateStatusBroadcast(0)
    }

    private fun updateStatusBroadcast(clientCount: Int) {
        val statusIntent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_CLIENT_COUNT, clientCount)
            putExtra(EXTRA_IS_STREAMING, true)
            setPackage(packageName)
        }
        sendBroadcast(statusIntent)
    }

    private fun stopStreaming() {
        // Cleanup standard structures
        virtualDisplay?.release()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        mediaProjection?.stop()
        server?.stop()
        
        // Cleanup RTSP structural setups safely
        if (rtspServerDisplay?.isStreaming == true) {
            rtspServerDisplay?.stopStream()
        }

        backgroundThread?.quitSafely()
        networkExecutor.shutdownNow()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        val statusIntent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_CLIENT_COUNT, 0)
            putExtra(EXTRA_IS_STREAMING, false)
            setPackage(packageName)
        }
        sendBroadcast(statusIntent)

        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Stream",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    // --- ConnectCheckerRtsp Callbacks Implementation ---
    override fun onConnectionSuccessRtsp() {
        Log.i(TAG, "Rtsp client connected successfully")
        // Note: The library manages its own internal connections array.
        // We broadcast an updated client status approximation safely.
        updateStatusBroadcast(1)
    }
    
    override fun onConnectionFailedRtsp(reason: String) {
        Log.e(TAG, "Rtsp client link creation failed: $reason")
    }
    
    override fun onNewBitrateRtsp(bitrate: Long) {}
    
    override fun onDisconnectRtsp() {
        Log.i(TAG, "Rtsp client session closed.")
        updateStatusBroadcast(0)
    }
    
    override fun onAuthErrorRtsp() { Log.e(TAG, "RTSP Auth verification error occurred") }
    override fun onAuthSuccessRtsp() { Log.i(TAG, "RTSP Authentication matched") }
}
