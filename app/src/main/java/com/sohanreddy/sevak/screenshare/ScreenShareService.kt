package com.sohanreddy.sevak.screenshare

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sohanreddy.sevak.MainActivity
import com.sohanreddy.sevak.R
import com.sohanreddy.sevak.ui.main.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class ScreenShareService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private var overlayView: VoiceBubbleOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val assistantViewModel: MainViewModel by lazy { MainViewModel(application) }

    private var lastFrameCaptureMs: Long = 0L
    private var isStopping = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopScreenShareAndRestore(restoreApp = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startFromProjectionResult(intent)
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startFromProjectionResult(intent: Intent) {
        if (isStopping) return

        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission missing, cannot start bubble")
            stopSelf()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intentParcelable(intent, EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection result payload")
            stopSelf()
            return
        }

        startAsForegroundService()

        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection")
            stopSelf()
            return
        }

        mediaProjection = projection
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system/user")
                stopScreenShareAndRestore(restoreApp = true)
            }
        }, Handler(mainLooper))

        if (!startImageCapturePipeline()) {
            Log.e(TAG, "Failed to start image capture pipeline")
            stopScreenShareAndRestore(restoreApp = false)
            return
        }

        showOverlayBubble()
        observeAssistantState()
        assistantViewModel.enableLiveModeIfNeeded()

        ScreenShareSessionManager.setActive(true)
        Log.i(TAG, "Screen share started")
    }

    private fun startAsForegroundService() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Saathi Live Screen Share")
            .setContentText("Screen is shared. Tap Stop to end.")
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()

        val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            serviceType
        )
    }

    private fun startImageCapturePipeline(): Boolean {
        val projection = mediaProjection ?: return false

        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val densityDpi = resources.configuration.densityDpi.coerceAtLeast(1)

        stopImageCapturePipeline()

        imageThread = HandlerThread("saathi-screen-reader").also { it.start() }
        imageHandler = Handler(imageThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            onImageAvailable(reader)
        }, imageHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "SaathiScreenCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            imageHandler
        )

        return virtualDisplay != null
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed: ${e.message}")
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameCaptureMs < FRAME_THROTTLE_MS) {
                return
            }
            lastFrameCaptureMs = now

            val bitmap = imageToBitmap(image) ?: return
            val resized = resizeForModel(bitmap)
            if (resized !== bitmap) {
                bitmap.recycle()
            }

            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            ScreenShareSessionManager.updateLatestScreenshot(base64)
            out.close()
            resized.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Failed processing capture frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val wideBitmap = Bitmap.createBitmap(
                image.width + (rowPadding / pixelStride),
                image.height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            wideBitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(wideBitmap, 0, 0, image.width, image.height)
            if (cropped !== wideBitmap) {
                wideBitmap.recycle()
            }
            cropped
        } catch (e: Exception) {
            Log.w(TAG, "imageToBitmap failed: ${e.message}")
            null
        }
    }

    private fun resizeForModel(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_IMAGE_WIDTH) return bitmap
        val scaledHeight = (bitmap.height * (MAX_IMAGE_WIDTH.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, MAX_IMAGE_WIDTH, scaledHeight, true)
    }

    private fun showOverlayBubble() {
        if (overlayView != null) return

        val bubbleSizePx = dp(BUBBLE_SIZE_DP)
        val displayBounds = windowManager.currentWindowMetrics.bounds

        val params = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayBounds.width() - bubbleSizePx - dp(16f)).coerceAtLeast(0)
            y = (displayBounds.height() - bubbleSizePx - dp(120f)).coerceAtLeast(0)
        }

        val bubble = VoiceBubbleOverlayView(this)
        attachDragAndTapBehavior(bubble, params)

        windowManager.addView(bubble, params)
        overlayView = bubble
        overlayParams = params
    }

    private fun attachDragAndTapBehavior(
        bubble: VoiceBubbleOverlayView,
        params: WindowManager.LayoutParams
    ) {
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!dragging && (abs(dx) > dp(3f).toFloat() || abs(dy) > dp(3f).toFloat())) {
                            dragging = true
                            bubble.hideCloseButton()
                        }
                        if (dragging) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            overlayParams = params
                            windowManager.updateViewLayout(bubble, params)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            val localX = event.x
                            val localY = event.y
                            if (bubble.isPointOnCloseButton(localX, localY) && bubble.isCloseButtonVisible()) {
                                stopScreenShareAndRestore(restoreApp = true)
                            } else {
                                bubble.toggleCloseButton()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun observeAssistantState() {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            assistantViewModel.state.collectLatest { state ->
                overlayView?.updateAudioState(state.assistantState, state.audioAmplitude)
            }
        }
    }

    private fun stopScreenShareAndRestore(restoreApp: Boolean) {
        if (isStopping) return
        isStopping = true

        ScreenShareSessionManager.setActive(false)

        try {
            assistantViewModel.disableLiveModeIfEnabled()
        } catch (_: Exception) {
            // Ignore guard failures while stopping.
        }

        stateJob?.cancel()
        stateJob = null

        removeOverlayBubble()
        stopImageCapturePipeline()

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
            // Ignore stop races.
        }
        mediaProjection = null

        if (restoreApp) {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            runCatching { startActivity(launchIntent) }
        }

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun removeOverlayBubble() {
        val view = overlayView ?: return
        runCatching { windowManager.removeView(view) }
        overlayView = null
        overlayParams = null
    }

    private fun stopImageCapturePipeline() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null

        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null
    }

    override fun onDestroy() {
        stopScreenShareAndRestore(restoreApp = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Saathi Screen Share",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground service for live screen sharing"
        }
        manager.createNotificationChannel(channel)
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    @Suppress("DEPRECATION")
    private fun intentParcelable(intent: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Intent::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "saathi_screen_share"
        private const val NOTIFICATION_ID = 4201

        private const val BUBBLE_SIZE_DP = 104f
        private const val FRAME_THROTTLE_MS = 650L
        private const val JPEG_QUALITY = 68
        private const val MAX_IMAGE_WIDTH = 720

        const val ACTION_START = "com.sohanreddy.sevak.screenshare.START"
        const val ACTION_STOP = "com.sohanreddy.sevak.screenshare.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
