package com.mirrifytv.tv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mirrifytv.shared.connection.NetworkUtils
import com.mirrifytv.shared.connection.QRCodeUtils
import com.mirrifytv.shared.model.ConnectionInfo
import com.mirrifytv.shared.model.StreamingState
import com.mirrifytv.tv.service.ReceiverService
import com.mirrifytv.tv.ui.theme.TVAccent
import com.mirrifytv.tv.ui.theme.TVBgDark
import com.mirrifytv.tv.ui.theme.TVColorError
import com.mirrifytv.tv.ui.theme.TVMirrifyTheme
import com.mirrifytv.tv.ui.theme.TVSurface
import com.mirrifytv.tv.ui.theme.TVTextMuted
import com.mirrifytv.tv.ui.theme.TVTextPrimary
import com.mirrifytv.tv.ui.theme.TVTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class TVMainActivity : ComponentActivity() {

    private val TAG = "TVMainActivity"
    private val serverPort = 8765

    // Mutable state shared between coroutines and Compose
    private val sessionIdState = mutableStateOf(UUID.randomUUID().toString().take(8))
    private val qrBitmapState = mutableStateOf<Bitmap?>(null)
    private val ipAddressState = mutableStateOf<String?>(null)
    private val wifiErrorState = mutableStateOf(false)
    private val currentFrameState = mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startReceiverService()
        refreshQRCode()

        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides this@TVMainActivity) {
                TVMirrifyTheme {
                    val state by ReceiverService.stateFlow.collectAsStateWithLifecycle()
                    val sessionId by sessionIdState
                    val qrBitmap by qrBitmapState
                    val ipAddress by ipAddressState
                    val wifiError by wifiErrorState
                    val currentFrame by currentFrameState

                    // Collect incoming frames
                    LaunchedEffect(Unit) {
                        ReceiverService.frameFlow.collect { frame ->
                            currentFrameState.value =
                                BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                        }
                    }

                    // On disconnect, generate new session and refresh QR
                    LaunchedEffect(state) {
                        if (state is StreamingState.Disconnected) {
                            sessionIdState.value = UUID.randomUUID().toString().take(8)
                            refreshQRCode()
                        }
                    }

                    TVScreen(
                        state = state,
                        qrBitmap = qrBitmap,
                        ipAddress = ipAddress,
                        sessionId = sessionId,
                        wifiError = wifiError,
                        currentFrame = currentFrame,
                    )
                }
            }
        }
    }

    private fun refreshQRCode() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ip = NetworkUtils.getWifiIpAddress(applicationContext)
            withContext(Dispatchers.Main) {
                if (ip == null) {
                    wifiErrorState.value = true
                    ipAddressState.value = null
                    qrBitmapState.value = null
                    return@withContext
                }
                wifiErrorState.value = false
                ipAddressState.value = ip
                val json = ConnectionInfo(ip, serverPort, sessionIdState.value).toJson()
                Log.d(TAG, "QR content: $json")
                qrBitmapState.value = QRCodeUtils.generateQRCode(json, 400)
            }
        }
    }

    private fun startReceiverService() {
        startService(Intent(this, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_START
            putExtra(ReceiverService.EXTRA_PORT, serverPort)
        })
    }

    override fun onDestroy() {
        startService(Intent(this, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_STOP
        })
        super.onDestroy()
    }
}

@Composable
private fun TVScreen(
    state: StreamingState,
    qrBitmap: Bitmap?,
    ipAddress: String?,
    sessionId: String,
    wifiError: Boolean,
    currentFrame: Bitmap?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TVBgDark),
    ) {
        when (state) {
            is StreamingState.Streaming -> {
                // Full-screen stream view
                currentFrame?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Screen stream from mobile device",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                LiveBadge(modifier = Modifier.align(Alignment.TopEnd).padding(24.dp))
            }

            is StreamingState.Connecting -> {
                TVWaitingLayout(qrBitmap, ipAddress, sessionId, wifiError)
                ConnectingOverlay()
            }

            is StreamingState.Connected -> {
                TVWaitingLayout(qrBitmap, ipAddress, sessionId, wifiError)
                ConnectedOverlay()
            }

            is StreamingState.Error -> {
                TVWaitingLayout(qrBitmap, ipAddress, sessionId, wifiError)
                ErrorBadge(
                    message = state.message,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                )
            }

            else -> TVWaitingLayout(qrBitmap, ipAddress, sessionId, wifiError)
        }
    }
}

@Composable
private fun TVWaitingLayout(
    qrBitmap: Bitmap?,
    ipAddress: String?,
    sessionId: String,
    wifiError: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        // Left — branding + info + instructions
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "MirrifyTV",
                color = TVTextPrimary,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Screen Mirroring Receiver",
                color = TVAccent,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(32.dp))

            if (wifiError) {
                Text(
                    text = "⚠  Connect to WiFi to receive streams",
                    color = TVColorError,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                ipAddress?.let {
                    Text(
                        text = "Host: $it:8765",
                        color = TVTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = "Session: $sessionId",
                    color = TVTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(40.dp))

            listOf(
                "1. Install MirrifyTV on your phone",
                "2. Open the app and tap \"Scan QR Code\"",
                "3. Point your camera at the QR code →",
                "4. Streaming begins automatically",
            ).forEach { step ->
                Text(
                    text = step,
                    color = TVTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }

        // Right — QR code card
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .padding(20.dp),
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR code to connect mobile device",
                        modifier = Modifier.size(280.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = TVAccent, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text("Connecting…", color = TVTextPrimary, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ConnectedOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBB000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✓", color = Color(0xFF4CAF50), fontSize = 64.sp)
            Spacer(Modifier.height(8.dp))
            Text("Phone Connected!", color = TVTextPrimary, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(TVColorError.copy(alpha = dotAlpha), CircleShape)
        )
        Text(
            text = "LIVE",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ErrorBadge(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TVColorError.copy(alpha = 0.9f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text("Error: $message", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}
