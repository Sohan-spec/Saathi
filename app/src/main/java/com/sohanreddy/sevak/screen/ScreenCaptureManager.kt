package com.sohanreddy.sevak.screen

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures a single screenshot using Android MediaProjection API.
 * Keeps a persistent VirtualDisplay + ImageReader so the projection token
 * is always active and frames are continuously available for instant capture.
 */
object ScreenCaptureManager {

    private const val TAG = "ScreenCapture"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    // Holds the latest frame bitmap — updated by ImageReader callback
    private val latestFrame = AtomicReference<Bitmap?>(null)

    var screenWidth: Int = 1080
        private set
    var screenHeight: Int = 1920
        private set
    var screenDensity: Int = 420
        private set

    fun init(projection: MediaProjection, width: Int, height: Int, density: Int) {
        release() // clean up any prior session
        mediaProjection = projection
        screenWidth = width
        screenHeight = height
        screenDensity = density
        Log.d(TAG, "Initialized: ${width}x${height} @ ${density}dpi")

        // Create persistent ImageReader + VirtualDisplay
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        // Set up callback so we always have the latest frame
        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = imageToBitmap(image, width, height)
                // Replace old frame
                val old = latestFrame.getAndSet(bitmap)
                if (old != null && old != bitmap) {
                    old.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame conversion error: ${e.message}")
            } finally {
                image.close()
            }
        }, handler)

        // Android 14+ REQUIRES registering a callback before createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    release()
                }
            }, handler)
            Log.d(TAG, "MediaProjection.Callback registered (Android 14+)")
        }

        val display = projection.createVirtualDisplay(
            "SaathiScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        virtualDisplay = display
        Log.d(TAG, "Persistent VirtualDisplay created")
    }

    /**
     * Capture the current screen content. Returns the latest available frame.
     * Because the VirtualDisplay is persistent, this is near-instant.
     */
    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection not initialized")
            return@withContext null
        }

        // Wait briefly for a frame if none available yet
        var attempts = 0
        while (latestFrame.get() == null && attempts < 10) {
            delay(100)
            attempts++
        }

        val frame = latestFrame.get()
        if (frame == null) {
            Log.w(TAG, "No frame available after waiting")
            return@withContext null
        }

        // Return a copy so the callback can keep updating latestFrame independently
        val copy = frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)
        Log.d(TAG, "Screenshot captured: ${copy.width}x${copy.height}")
        copy
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop out padding if present
        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            if (cropped != bitmap) bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }

    fun isReady(): Boolean = mediaProjection != null

    fun release() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing: ${e.message}")
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        latestFrame.getAndSet(null)?.recycle()
        Log.d(TAG, "Released all resources")
    }
}
