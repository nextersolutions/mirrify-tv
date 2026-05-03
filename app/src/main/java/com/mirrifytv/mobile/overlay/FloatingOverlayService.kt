package com.mirrifytv.mobile.overlay

import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mirrifytv.mobile.service.ScreenStreamingService
import com.mirrifytv.mobile.ui.theme.MirrifyTheme
import com.mirrifytv.shared.model.StreamingState
import kotlinx.coroutines.launch

class FloatingOverlayService : LifecycleService() {

    companion object {
        const val ACTION_SHOW = "com.mirrifytv.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.mirrifytv.HIDE_OVERLAY"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    private val vmStore = ViewModelStore()
    private val vmStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore
            get() = vmStore
    }

    private inner class OverlaySavedStateOwner : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = this@FloatingOverlayService.lifecycle
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        fun setup() {
            controller.performAttach()
            controller.performRestore(null)
        }
    }

    private lateinit var savedStateOwner: OverlaySavedStateOwner

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        savedStateOwner = OverlaySavedStateOwner().also { it.setup() }

        // Auto-dismiss overlay when streaming stops
        lifecycleScope.launch {
            ScreenStreamingService.stateFlow.collect { state ->
                when (state) {
                    is StreamingState.Idle,
                    is StreamingState.Disconnected,
                    is StreamingState.Error -> {
                        removeOverlay()
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 72
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeViewModelStoreOwner(vmStoreOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                MirrifyTheme {
                    StreamingOverlayPill(onStop = ::stopStreaming)
                }
            }
        }

        overlayView = view
        windowManager.addView(view, params)
    }

    private fun stopStreaming() {
        startService(Intent(this, ScreenStreamingService::class.java).apply {
            action = ScreenStreamingService.ACTION_STOP
        })
        // The state flow change will trigger removeOverlay + stopSelf via the collector above
    }

    private fun removeOverlay() {
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
            overlayView = null
        }
    }

    override fun onDestroy() {
        removeOverlay()
        vmStore.clear()
        super.onDestroy()
    }
}

@Composable
private fun StreamingOverlayPill(onStop: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "dot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC000000))        // semi-transparent black
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Blinking red dot — mirrors the "recording" indicator convention
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Color.Red.copy(alpha = dotAlpha), CircleShape)
        )

        Text(
            text = "Mirroring",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )

        // Stop button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFE53935))
                .clickable(onClick = onStop)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = "Stop",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
