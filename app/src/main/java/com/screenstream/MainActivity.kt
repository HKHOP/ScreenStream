package com.screenstream

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREF_IS_STREAMING = "pref_is_streaming"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvClients: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSettings: Button
    private lateinit var cardStream: View

    private lateinit var projectionManager: MediaProjectionManager
    private var isStreaming = false
    private var rtspMode = false
    private var audioRequested = false
    private var audioActive = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val count = intent?.getIntExtra(ScreenStreamService.EXTRA_CLIENT_COUNT, 0) ?: 0
            val streaming = intent?.getBooleanExtra(ScreenStreamService.EXTRA_IS_STREAMING, false) ?: false
            rtspMode = intent?.getBooleanExtra(ScreenStreamService.EXTRA_RTSP_MODE, false) ?: false
            audioRequested = intent?.getBooleanExtra(ScreenStreamService.EXTRA_AUDIO_REQUESTED, false) ?: false
            audioActive = intent?.getBooleanExtra(ScreenStreamService.EXTRA_AUDIO_ACTIVE, false) ?: false
            if (streaming) showStreaming() else showIdle()
            updateClientCount(count)
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startStreamingService(result.resultCode, result.data!!)
            } else {
                showIdle()
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                showIdle()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnToggle.setOnClickListener {
            if (isStreaming) stopStreaming() else startCaptureFlow()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        tvClients = findViewById(R.id.tvClients)
        tvHint = findViewById(R.id.tvHint)
        btnToggle = findViewById(R.id.btnToggle)
        btnSettings = findViewById(R.id.btnSettings)
        cardStream = findViewById(R.id.cardStream)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            statusReceiver,
            IntentFilter(ScreenStreamService.BROADCAST_STATUS),
            RECEIVER_NOT_EXPORTED
        )
        syncUiWithServiceState()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    override fun onResume() {
        super.onResume()
        updateUrl()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startCaptureFlow() {
        if (requiresMicPermissionForCurrentSettings() &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun requiresMicPermissionForCurrentSettings(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rtsp = prefs.getBoolean("use_rtsp_mode", false)
        val audioEnabled = prefs.getBoolean("enable_audio", false)
        val audioSource = prefs.getString("audio_source", "mic") ?: "mic"
        return rtsp && audioEnabled && audioSource == "mic"
    }

    private fun startStreamingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenStreamService::class.java).apply {
            action = ScreenStreamService.ACTION_START
            putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenStreamService.EXTRA_PROJECTION_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Wait for service broadcast to confirm actual state.
    }

    private fun stopStreaming() {
        startService(Intent(this, ScreenStreamService::class.java).apply {
            action = ScreenStreamService.ACTION_STOP
        })
        // Wait for service broadcast to confirm actual state.
    }

    private fun showStreaming() {
        isStreaming = true

        btnToggle.text = "Stop"
        tvStatus.text = if (audioActive) "● LIVE (audio+video)" else "● LIVE (video-only)"
        cardStream.visibility = View.VISIBLE
        tvHint.visibility = View.VISIBLE

        updateUrl()
    }

    private fun showIdle() {
        isStreaming = false

        btnToggle.text = "Start"
        tvStatus.text = "● Idle"
        cardStream.visibility = View.GONE
        tvHint.visibility = View.GONE

        tvClients.text = "No viewers"
        updateUrl()
    }

    private fun updateClientCount(count: Int) {
        tvClients.text =
            if (count <= 0) "No viewers connected"
            else "$count viewer${if (count > 1) "s" else ""} connected"
    }

    private fun updateUrl() {
        val ip = getLocalIp()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rtsp = prefs.getBoolean("use_rtsp_mode", false)
        val audioEnabled = prefs.getBoolean("enable_audio", false)
        rtspMode = rtsp
        audioRequested = audioEnabled

        tvUrl.text = if (rtsp) {
            val port = prefs.getString("rtsp_port", "1935")
            "rtsp://$ip:$port/"
        } else {
            "http://$ip:${ScreenStreamService.PORT}"
        }

        val modeHint = when {
            rtsp && audioEnabled -> "Mode: RTSP (audio+video available)"
            rtsp -> "Mode: RTSP (video-only; audio currently disabled in settings)"
            else -> "Mode: MJPEG HTTP (video-only). Audio is available only in RTSP mode."
        }
        val warning = if (!rtsp && audioEnabled) {
            "\nAudio is enabled in settings, but MJPEG mode ignores audio."
        } else ""
        tvHint.text = "How to watch:\n1. Open VLC on your TV, PC, or phone\n2. Go to Media → Open Network Stream\n3. Paste the URL above and press Play\n\n$modeHint$warning"
    }

    private fun syncUiWithServiceState() {
        val streaming = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_IS_STREAMING, false)
        if (streaming) showStreaming() else showIdle()
    }

    private fun getLocalIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull {
                    !it.isLoopbackAddress && it is Inet4Address
                }?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
