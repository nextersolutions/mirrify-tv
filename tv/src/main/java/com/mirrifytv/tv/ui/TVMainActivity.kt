package com.mirrifytv.tv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mirrifytv.shared.connection.NetworkUtils
import com.mirrifytv.shared.connection.QRCodeUtils
import com.mirrifytv.shared.model.ConnectionInfo
import com.mirrifytv.shared.model.StreamingState
import com.mirrifytv.tv.R
import com.mirrifytv.tv.databinding.ActivityTvMainBinding
import com.mirrifytv.tv.service.ReceiverService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class TVMainActivity : AppCompatActivity() {

    private val TAG = "TVMainActivity"
    private lateinit var binding: ActivityTvMainBinding

    private val serverPort = 8765
    private var sessionId = UUID.randomUUID().toString().take(8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupQRCode()
        startReceiverService()
        observeReceiverState()
    }

    private fun setupQRCode() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ip = NetworkUtils.getWifiIpAddress(applicationContext)
            if (ip == null) {
                withContext(Dispatchers.Main) {
                    binding.tvWifiStatus.text = getString(R.string.wifi_not_connected)
                    binding.tvWifiStatus.visibility = View.VISIBLE
                    binding.qrCard.visibility = View.GONE
                }
                return@launch
            }

            val info = ConnectionInfo(
                host = ip,
                port = serverPort,
                sessionId = sessionId
            )
            val json = info.toJson()
            Log.d(TAG, "QR content: $json")

            val qrBitmap = QRCodeUtils.generateQRCode(json, 400)

            withContext(Dispatchers.Main) {
                binding.ivQrCode.setImageBitmap(qrBitmap)
                binding.tvIpAddress.text = getString(R.string.ip_address, ip, serverPort)
                binding.tvSessionId.text = getString(R.string.session_label, sessionId)
                binding.qrCard.visibility = View.VISIBLE
                binding.tvWifiStatus.visibility = View.GONE
            }
        }
    }

    private fun startReceiverService() {
        val intent = Intent(this, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_START
            putExtra(ReceiverService.EXTRA_PORT, serverPort)
        }
        startService(intent)
    }

    private fun observeReceiverState() {
        lifecycleScope.launch {
            ReceiverService.stateFlow.collect { state ->
                handleState(state)
            }
        }

        // Observe incoming frames
        lifecycleScope.launch {
            ReceiverService.frameFlow.collect { frame ->
                val bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                if (bitmap != null) {
                    binding.ivStreamView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun handleState(state: StreamingState) {
        when (state) {
            is StreamingState.Idle -> showWaiting()
            is StreamingState.Connecting -> showConnecting()
            is StreamingState.Connected -> showConnected(state.sessionId)
            is StreamingState.Streaming -> showStreaming()
            is StreamingState.Error -> showError(state.message)
            is StreamingState.Disconnected -> {
                showWaiting()
                // Generate new session on disconnect
                sessionId = UUID.randomUUID().toString().take(8)
                setupQRCode()
            }
        }
    }

    private fun showWaiting() {
        binding.qrCard.visibility = View.VISIBLE
        binding.ivStreamView.visibility = View.GONE
        binding.overlayWaiting.visibility = View.VISIBLE
        binding.overlayConnecting.visibility = View.GONE
        binding.overlayConnected.visibility = View.GONE
        binding.tvStreamStatus.visibility = View.GONE
    }

    private fun showConnecting() {
        binding.overlayConnecting.visibility = View.VISIBLE
        binding.overlayWaiting.visibility = View.GONE
    }

    private fun showConnected(sessionId: String) {
        binding.overlayConnected.visibility = View.VISIBLE
        binding.overlayConnecting.visibility = View.GONE
        binding.overlayWaiting.visibility = View.GONE
    }

    private fun showStreaming() {
        binding.qrCard.visibility = View.GONE
        binding.ivStreamView.visibility = View.VISIBLE
        binding.overlayWaiting.visibility = View.GONE
        binding.overlayConnecting.visibility = View.GONE
        binding.overlayConnected.visibility = View.GONE
        binding.tvStreamStatus.visibility = View.VISIBLE
        binding.tvStreamStatus.text = getString(R.string.streaming_live)
    }

    private fun showError(message: String) {
        binding.tvWifiStatus.text = getString(R.string.error_label, message)
        binding.tvWifiStatus.visibility = View.VISIBLE
        binding.overlayConnecting.visibility = View.GONE
        binding.overlayConnected.visibility = View.GONE
    }

    override fun onDestroy() {
        val intent = Intent(this, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_STOP
        }
        startService(intent)
        super.onDestroy()
    }
}
