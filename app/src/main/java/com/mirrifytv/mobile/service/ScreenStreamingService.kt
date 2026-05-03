package com.mirrifytv.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mirrifytv.mobile.R
import com.mirrifytv.mobile.ui.MainActivity
import com.mirrifytv.shared.model.StreamingState
import com.mirrifytv.shared.streaming.ScreenCaptureHelper
import com.mirrifytv.shared.streaming.StreamSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScreenStreamingService : Service() {

    companion object {
        const val ACTION_START = "com.mirrifytv.START_STREAM"
        const val ACTION_STOP = "com.mirrifytv.STOP_STREAM"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_SESSION_ID = "session_id"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mirrifytv_stream"

        private val _stateFlow = MutableStateFlow<StreamingState>(StreamingState.Idle)
        val stateFlow: StateFlow<StreamingState> = _stateFlow
    }

    private val TAG = "ScreenStreamingService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaProjection: MediaProjection? = null
    private var captureHelper: ScreenCaptureHelper? = null
    private var streamSender: StreamSender? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 8765)
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY

                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                startStreaming(resultCode, projectionData!!, host, port, sessionId)
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(
        resultCode: Int,
        data: Intent,
        host: String,
        port: Int,
        sessionId: String
    ) {
        _stateFlow.value = StreamingState.Connecting

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            _stateFlow.value = StreamingState.Error("Failed to get screen capture permission")
            stopSelf()
            return
        }

        // Register callback to handle projection end
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopStreaming()
                stopSelf()
            }
        }, null)

        streamSender = StreamSender(host, port, sessionId)
        captureHelper = ScreenCaptureHelper(applicationContext, mediaProjection!!)

        streamSender!!.connect(
            onConnected = {
                Log.d(TAG, "Connected to TV")
                _stateFlow.value = StreamingState.Connected(sessionId)

                // Start capture and begin piping frames
                captureHelper!!.startCapture()
                _stateFlow.value = StreamingState.Streaming
                updateNotification("Streaming to TV")

                scope.launch {
                    captureHelper!!.frameFlow.collect { frame ->
                        streamSender?.sendFrame(frame)
                    }
                }
            },
            onDisconnected = {
                Log.d(TAG, "Disconnected from TV")
                _stateFlow.value = StreamingState.Disconnected
                stopStreaming()
                stopSelf()
            }
        )
    }

    private fun stopStreaming() {
        captureHelper?.stopCapture()
        captureHelper = null
        streamSender?.disconnect()
        streamSender = null
        mediaProjection?.stop()
        mediaProjection = null
        _stateFlow.value = StreamingState.Idle
        scope.cancel()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MirrifyTV screen streaming"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, ScreenStreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MirrifyTV")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_stop, "Stop", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
