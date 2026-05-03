package com.mirrifytv.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirrifytv.mobile.service.ScreenStreamingService
import com.mirrifytv.mobile.ui.theme.Accent
import com.mirrifytv.mobile.ui.theme.BgDark
import com.mirrifytv.mobile.ui.theme.ColorError
import com.mirrifytv.mobile.ui.theme.ColorSuccess
import com.mirrifytv.mobile.ui.theme.MirrifyTheme
import com.mirrifytv.mobile.ui.theme.Surface
import com.mirrifytv.mobile.ui.theme.TextMuted
import com.mirrifytv.mobile.ui.theme.TextPrimary
import com.mirrifytv.mobile.ui.theme.TextSecondary
import com.mirrifytv.shared.model.ConnectionInfo
import com.mirrifytv.shared.model.StreamingState

class MainActivity : ComponentActivity() {

    private var pendingConnectionInfo: ConnectionInfo? = null

    // Tracks whether the QR scanner screen is shown
    private val showScanner = mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showScanner.value = true
        else ScreenStreamingService.setError("Camera permission required to scan QR code")
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val info = pendingConnectionInfo ?: return@registerForActivityResult
            startStreamingService(result.resultCode, result.data!!, info)
        } else {
            ScreenStreamingService.setError("Screen capture permission denied")
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* canDrawOverlays re-checked on next recompose */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()

        setContent {
            MirrifyTheme {
                val streamingState by ScreenStreamingService.stateFlow.collectAsStateWithLifecycle()
                val scanning by showScanner
                val canDrawOverlays = mutableStateOf(checkOverlayPermission()).value

                AnimatedContent(
                    targetState = scanning,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label = "screen",
                ) { isScanning ->
                    if (isScanning) {
                        QRScannerScreen(
                            onQRCodeScanned = { json -> handleQRCode(json) },
                            onDismiss = { showScanner.value = false },
                        )
                    } else {
                        MainScreen(
                            state = streamingState,
                            canDrawOverlays = canDrawOverlays,
                            onScanQR = { requestCameraAndScan() },
                            onStop = { stopStreaming() },
                            onRequestOverlayPermission = { requestOverlayPermission() },
                        )
                    }
                }
            }
        }
    }

    private fun requestCameraAndScan() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> showScanner.value = true
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun handleQRCode(json: String) {
        showScanner.value = false
        try {
            pendingConnectionInfo = ConnectionInfo.fromJson(json)
            requestMediaProjection()
        } catch (e: Exception) {
            ScreenStreamingService.setError("Invalid QR code")
        }
    }

    private fun checkOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestMediaProjection() {
        ScreenStreamingService.setConnecting()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startStreamingService(resultCode: Int, data: Intent, info: ConnectionInfo) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ScreenStreamingService::class.java).apply {
                action = ScreenStreamingService.ACTION_START
                putExtra(ScreenStreamingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenStreamingService.EXTRA_PROJECTION_DATA, data)
                putExtra(ScreenStreamingService.EXTRA_HOST, info.host)
                putExtra(ScreenStreamingService.EXTRA_PORT, info.port)
                putExtra(ScreenStreamingService.EXTRA_SESSION_ID, info.sessionId)
            }
        )
    }

    private fun stopStreaming() {
        startService(Intent(this, ScreenStreamingService::class.java).apply {
            action = ScreenStreamingService.ACTION_STOP
        })
    }
}

@Composable
private fun MainScreen(
    state: StreamingState,
    canDrawOverlays: Boolean,
    onScanQR: () -> Unit,
    onStop: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
) {
    val isIdle = state is StreamingState.Idle
        || state is StreamingState.Error
        || state is StreamingState.Disconnected
    val isActive = state is StreamingState.Streaming || state is StreamingState.Connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = "MirrifyTV",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Cast your screen to TV",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(Modifier.height(40.dp))

        StatusCard(state = state)

        Spacer(Modifier.height(24.dp))

        if (!canDrawOverlays) {
            OverlayPermissionBanner(onRequest = onRequestOverlayPermission)
            Spacer(Modifier.height(16.dp))
        }

        AnimatedVisibility(visible = isIdle, enter = fadeIn(), exit = fadeOut()) {
            Button(
                onClick = onScanQR,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) {
                Text(
                    "Scan QR Code on TV",
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        AnimatedVisibility(visible = isActive, enter = fadeIn(), exit = fadeOut()) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorError),
            ) {
                Text(
                    "Stop Streaming",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        AnimatedVisibility(visible = isIdle, enter = fadeIn(), exit = fadeOut()) {
            Column(
                modifier = Modifier.padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "1. Open MirrifyTV on your TV\n" +
                        "2. Tap \"Scan QR Code\" here\n" +
                        "3. Point your camera at the TV screen\n" +
                        "4. Streaming begins automatically",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: StreamingState) {
    val statusText: String
    val statusColor: Color
    val showProgress: Boolean
    val sessionText: String?

    when (state) {
        is StreamingState.Idle -> {
            statusText = "Ready to cast"
            statusColor = TextSecondary
            showProgress = false
            sessionText = null
        }
        is StreamingState.Connecting -> {
            statusText = "Connecting to TV…"
            statusColor = Accent
            showProgress = true
            sessionText = null
        }
        is StreamingState.Connected -> {
            statusText = "Connected"
            statusColor = ColorSuccess
            showProgress = false
            sessionText = "Session: ${state.sessionId}"
        }
        is StreamingState.Streaming -> {
            statusText = "Streaming"
            statusColor = ColorError
            showProgress = false
            sessionText = null
        }
        is StreamingState.Error -> {
            statusText = "Error: ${state.message}"
            statusColor = ColorError
            showProgress = false
            sessionText = null
        }
        is StreamingState.Disconnected -> {
            statusText = "Disconnected"
            statusColor = TextSecondary
            showProgress = false
            sessionText = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showProgress) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            } else if (state is StreamingState.Streaming) {
                StreamingDot()
            }
            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            sessionText?.let {
                Text(text = it, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StreamingDot() {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier = Modifier.size(12.dp).background(ColorError.copy(alpha = alpha), CircleShape))
}

@Composable
private fun OverlayPermissionBanner(onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Allow overlay for floating stop button",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onRequest, modifier = Modifier.padding(start = 8.dp)) {
            Text("Grant", style = MaterialTheme.typography.labelSmall)
        }
    }
}
