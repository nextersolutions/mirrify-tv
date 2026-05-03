package com.mirrifytv.shared.streaming

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import com.mirrifytv.shared.model.StreamFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class ScreenCaptureHelper(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    private val TAG = "ScreenCaptureHelper"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _frameFlow = MutableSharedFlow<StreamFrame>(extraBufferCapacity = 30)
    val frameFlow: SharedFlow<StreamFrame> = _frameFlow

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // Target resolution for streaming (scaled down to save bandwidth)
    private val streamWidth: Int
    private val streamHeight: Int

    init {
        val metrics = context.resources.displayMetrics
        // Stream at half resolution to reduce bandwidth
        streamWidth = (metrics.widthPixels / 2) and -2   // ensure even
        streamHeight = (metrics.heightPixels / 2) and -2
        Log.d(TAG, "Stream resolution: ${streamWidth}x${streamHeight}")
    }

    fun startCapture() {
        val metrics = context.resources.displayMetrics
        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            streamWidth, streamHeight,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "MirrifyTV",
            streamWidth,
            streamHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * streamWidth

                val bitmap = android.graphics.Bitmap.createBitmap(
                    streamWidth + rowPadding / pixelStride,
                    streamHeight,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to exact dimensions if needed
                val croppedBitmap = if (bitmap.width != streamWidth || bitmap.height != streamHeight) {
                    android.graphics.Bitmap.createBitmap(bitmap, 0, 0, streamWidth, streamHeight)
                } else {
                    bitmap
                }

                // Compress to JPEG
                val outputStream = java.io.ByteArrayOutputStream()
                croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
                val jpegBytes = outputStream.toByteArray()

                if (croppedBitmap !== bitmap) croppedBitmap.recycle()
                bitmap.recycle()

                scope.launch {
                    _frameFlow.emit(
                        StreamFrame(
                            data = jpegBytes,
                            width = streamWidth,
                            height = streamHeight
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            } finally {
                image.close()
            }
        }, handler)

        Log.d(TAG, "Screen capture started")
    }

    fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        scope.cancel()
        Log.d(TAG, "Screen capture stopped")
    }
}
