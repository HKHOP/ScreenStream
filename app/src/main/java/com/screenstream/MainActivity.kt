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

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvClients: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSettings: Button // Added Settings Reference
    private lateinit var cardStream: View

    private lateinit var projectionManager: MediaProjectionManager
    private var isStreaming = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val count = intent?.getIntExtra(ScreenStreamService.EXTRA_CLIENT_COUNT, 0) ?: 0
            updateClientCount(count)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchService(result.resultCode, result.data!!)
        } else {
            showIdle()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus   = findViewById(R.id.tvStatus)
        tvUrl      = findViewById(R.id.tvUrl)
        tvClients  = findViewById(R.id.tvClients)
        tvHint     = findViewById(R.id.tvHint)
        btnToggle  = findViewById(R.id.btnToggle)
        cardStream = findViewById(R.id.cardStream)
        
        // Dynamic binding or creation of settings hook
        btnSettings = Button(this).apply { text = "Settings" } 
        // Tip: You can place an actual Button with id R.id.btnSettings in your activity_main.xml layout later!

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnToggle.setOnClickListener {
            if (!isStreaming) startFlow() else stopFlow()
        }
        
        // Handle clicking into Settings
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            statusReceiver,
            IntentFilter(ScreenStreamService.BROADCAST_STATUS),
            RECEIVER_NOT_EXPORTED
        )
        // Refresh display addresses if configurations were altered while in settings screen
        updateDisplayUrl()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun updateDisplayUrl() {
        val ip = getWifiIpAddress()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useRtsp = prefs.getBoolean("use_rtsp_mode", false)
        
        if (useRtsp) {
            val rtspPort = prefs.getString("rtsp_port", "1935")
            tvUrl.text = "rtsp://$ip:$rtspPort/"
        } else {
            tvUrl.text = "http://$ip:${ScreenStreamService.PORT}"
        }
    }

    private fun startFlow() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun launchService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenStreamService::class.java).apply {
            action = ScreenStreamService.ACTION_START
            putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenStreamService.EXTRA_PROJECTION_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        showStreaming()
    }

    private fun stopFlow() {
        startService(Intent(this, ScreenStreamService::class.java).apply {
            action = ScreenStreamService.ACTION_STOP
        })
        showIdle()
    }

    private fun showStreaming() {
        isStreaming = true
        btnToggle.text = "Stop Streaming"
        btnToggle.setBackgroundColor(getColor(android.R.color.holo_red_dark))
        tvStatus.text = "● LIVE"
        tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
        cardStream.visibility = View.VISIBLE
        tvClients.text = "0 viewers"
        tvHint.visibility = View.VISIBLE
        updateDisplayUrl()
    }

    private fun showIdle() {
        isStreaming = false
        btnToggle.text = "Start Streaming"
        btnToggle.setBackgroundColor(getColor(android.R.color.holo_blue_dark))
        tvStatus.text = "● Idle"
        tvStatus.setTextColor(getColor(android.R.color.darker_gray))
        cardStream.visibility = View.GONE
        tvHint.visibility = View.GONE
        updateDisplayUrl()
    }

    private fun updateClientCount(count: Int) {
        tvClients.text = if (count == 0) "No viewers connected"
                         else "$count viewer${if (count > 1) "s" else ""} connected  ✓"
    }

    private fun getWifiIpAddress(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.name.startsWith("wlan") && !intf.name.startsWith("eth")) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
