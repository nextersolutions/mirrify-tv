package com.mirrifytv.tv.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mirrifytv.shared.model.StreamFrame
import com.mirrifytv.shared.model.StreamingState
import com.mirrifytv.shared.streaming.StreamReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReceiverService : Service() {

    companion object {
        const val ACTION_START = "com.mirrifytv.tv.START_RECEIVER"
        const val ACTION_STOP = "com.mirrifytv.tv.STOP_RECEIVER"
        const val EXTRA_PORT = "port"

        private val _stateFlow = MutableStateFlow<StreamingState>(StreamingState.Idle)
        val stateFlow: StateFlow<StreamingState> = _stateFlow

        private val _frameFlow = MutableSharedFlow<StreamFrame>(extraBufferCapacity = 20)
        val frameFlow: SharedFlow<StreamFrame> = _frameFlow
    }

    private val TAG = "ReceiverService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamReceiver: StreamReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8765)
                startReceiver(port)
            }
            ACTION_STOP -> {
                stopReceiver()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startReceiver(port: Int) {
        Log.d(TAG, "Starting receiver on port $port")
        streamReceiver = StreamReceiver(port)
        streamReceiver!!.start()

        scope.launch {
            streamReceiver!!.clientConnected.collect { connected ->
                if (connected) {
                    Log.d(TAG, "Mobile client connected")
                    _stateFlow.value = StreamingState.Connected("active")
                } else {
                    Log.d(TAG, "Mobile client disconnected")
                    _stateFlow.value = StreamingState.Disconnected
                }
            }
        }

        scope.launch {
            streamReceiver!!.frameFlow.collect { frame ->
                // First frame means streaming started
                if (_stateFlow.value !is StreamingState.Streaming) {
                    _stateFlow.value = StreamingState.Streaming
                }
                _frameFlow.emit(frame)
            }
        }

        _stateFlow.value = StreamingState.Idle
    }

    private fun stopReceiver() {
        streamReceiver?.stop()
        streamReceiver = null
        _stateFlow.value = StreamingState.Idle
        scope.cancel()
    }

    override fun onDestroy() {
        stopReceiver()
        super.onDestroy()
    }
}
