package com.mirrifytv.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mirrifytv.mobile.R
import com.mirrifytv.mobile.databinding.ActivityMainBinding
import com.mirrifytv.mobile.service.ScreenStreamingService
import com.mirrifytv.shared.model.ConnectionInfo
import com.mirrifytv.shared.model.StreamingState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"

    private var pendingConnectionInfo: ConnectionInfo? = null

    // Request notification permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notification permission needed for background streaming", Toast.LENGTH_LONG).show()
        }
    }

    // Launch QR Scanner
    private val scanQRLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(ScanQRActivity.RESULT_CONNECTION_JSON)
            if (json != null) {
                try {
                    val info = ConnectionInfo.fromJson(json)
                    pendingConnectionInfo = info
                    Log.d(TAG, "Got connection info: $info")
                    requestMediaProjection(info)
                } catch (e: Exception) {
                    showError("Invalid QR code")
                }
            }
        }
    }

    // Request media projection permission
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val info = pendingConnectionInfo ?: return@registerForActivityResult
            startStreamingService(result.resultCode, result.data!!, info)
        } else {
            showError("Screen capture permission denied")
            setState(StreamingState.Idle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        requestNotificationPermission()
    }

    private fun setupUI() {
        binding.btnScanQr.setOnClickListener {
            val intent = Intent(this, ScanQRActivity::class.java)
            scanQRLauncher.launch(intent)
        }

        binding.btnStopStream.setOnClickListener {
            stopStreaming()
        }

        setState(StreamingState.Idle)

        // Observe streaming service state via broadcast or service binder
        // For simplicity, we poll / observe via local broadcast
        ScreenStreamingService.stateFlow.let { flow ->
            lifecycleScope.launch {
                flow.collect { state ->
                    setState(state)
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestMediaProjection(info: ConnectionInfo) {
        setState(StreamingState.Connecting)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startStreamingService(resultCode: Int, data: Intent, info: ConnectionInfo) {
        val intent = Intent(this, ScreenStreamingService::class.java).apply {
            action = ScreenStreamingService.ACTION_START
            putExtra(ScreenStreamingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenStreamingService.EXTRA_PROJECTION_DATA, data)
            putExtra(ScreenStreamingService.EXTRA_HOST, info.host)
            putExtra(ScreenStreamingService.EXTRA_PORT, info.port)
            putExtra(ScreenStreamingService.EXTRA_SESSION_ID, info.sessionId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreaming() {
        val intent = Intent(this, ScreenStreamingService::class.java).apply {
            action = ScreenStreamingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun setState(state: StreamingState) {
        runOnUiThread {
            when (state) {
                is StreamingState.Idle -> {
                    binding.tvStatus.text = getString(R.string.status_idle)
                    binding.btnScanQr.isEnabled = true
                    binding.btnStopStream.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    binding.tvConnectedTo.visibility = View.GONE
                }
                is StreamingState.Connecting -> {
                    binding.tvStatus.text = getString(R.string.status_connecting)
                    binding.btnScanQr.isEnabled = false
                    binding.btnStopStream.visibility = View.GONE
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvConnectedTo.visibility = View.GONE
                }
                is StreamingState.Connected -> {
                    binding.tvStatus.text = getString(R.string.status_connected)
                    binding.btnScanQr.isEnabled = false
                    binding.btnStopStream.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                    binding.tvConnectedTo.visibility = View.VISIBLE
                    binding.tvConnectedTo.text = getString(R.string.session_id, state.sessionId)
                }
                is StreamingState.Streaming -> {
                    binding.tvStatus.text = getString(R.string.status_streaming)
                    binding.btnScanQr.isEnabled = false
                    binding.btnStopStream.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                    binding.tvConnectedTo.visibility = View.VISIBLE
                }
                is StreamingState.Error -> {
                    binding.tvStatus.text = getString(R.string.status_error, state.message)
                    binding.btnScanQr.isEnabled = true
                    binding.btnStopStream.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    binding.tvConnectedTo.visibility = View.GONE
                }
                is StreamingState.Disconnected -> {
                    binding.tvStatus.text = getString(R.string.status_disconnected)
                    binding.btnScanQr.isEnabled = true
                    binding.btnStopStream.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    binding.tvConnectedTo.visibility = View.GONE
                }
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setState(StreamingState.Error(message))
    }
}
