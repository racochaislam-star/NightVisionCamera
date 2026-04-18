package com.nightvision.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.nightvision.camera.MainActivity
import com.nightvision.camera.R
import com.nightvision.camera.camera.NightVisionManager
import com.nightvision.camera.streaming.MjpegServer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StreamingService : LifecycleService() {

    private val TAG = "StreamingService"
    private val CHANNEL_ID = "streaming_channel"
    private val NOTIFICATION_ID = 1002
    private val STREAM_PORT = 8080

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    private val binder = LocalBinder()
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var nightVisionManager: NightVisionManager
    private lateinit var mjpegServer: MjpegServer

    var isStreaming = false
        private set
    var serverIp = ""
        private set
    var statusListener: ((String) -> Unit)? = null
    var frameRateListener: ((Int) -> Unit)? = null

    private var frameCount = 0
    private var lastFpsCheck = 0L
    private var currentFps = 0

    companion object {
        const val ACTION_START = "ACTION_START_STREAMING"
        const val ACTION_STOP = "ACTION_STOP_STREAMING"
        const val EXTRA_QUALITY = "EXTRA_QUALITY"
        const val EXTRA_NIGHT_MODE = "EXTRA_NIGHT_MODE"
        const val STREAM_PORT_DEFAULT = 8080
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        nightVisionManager = NightVisionManager(this)
        mjpegServer = MjpegServer(STREAM_PORT)
        createNotificationChannel()
        acquireWakeLock()
        serverIp = nightVisionManager.getDeviceIpAddress(this)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val streamUrl = "http://$serverIp:$STREAM_PORT/stream"
        startForeground(NOTIFICATION_ID, buildNotification("جارٍ البث: $streamUrl", true))

        when (intent?.action) {
            ACTION_START -> {
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "720p"
                val nightMode = intent.getBooleanExtra(EXTRA_NIGHT_MODE, true)
                startStreaming(quality, nightMode)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun startStreaming(quality: String, nightMode: Boolean) {
        // Start MJPEG server first
        mjpegServer.start()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Image Analysis use case for streaming frames
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(
                    if (quality == "1080p") android.util.Size(1920, 1080)
                    else android.util.Size(1280, 720)
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            // Apply Camera2 night vision settings
            if (nightMode) {
                val camera2Ext = Camera2Interop.Extender(analysisBuilder)
                camera2Ext.setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    nightVisionManager.isoLevel
                )
                camera2Ext.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    nightVisionManager.exposureNs
                )
                camera2Ext.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
                camera2Ext.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                )
            }

            val imageAnalysis = analysisBuilder.build()

            // Set analyzer - convert each frame to JPEG and send to MJPEG server
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy, nightMode)
            }

            try {
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )

                // Apply night vision and torch after binding
                if (nightMode && camera != null) {
                    nightVisionManager.enableNightVision(camera)
                    if (nightVisionManager.flashLevel != 0) {
                        camera.cameraControl.enableTorch(true)
                    }
                }

                isStreaming = true
                val streamUrl = "http://$serverIp:$STREAM_PORT/stream"
                updateNotification("🟢 بث مباشر | $streamUrl")
                statusListener?.invoke("streaming:$streamUrl")
                Log.i(TAG, "Streaming started at $streamUrl")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming: ${e.message}")
                statusListener?.invoke("error:${e.message}")
                stopSelf()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy, nightMode: Boolean) {
        try {
            // Auto night vision detection
            if (nightMode && nightVisionManager.isAutoMode) {
                val yPlane = imageProxy.planes[0]
                val yBuffer = yPlane.buffer
                val yBytes = ByteArray(yBuffer.remaining())
                yBuffer.get(yBytes)
                // (auto night vision logic already applied via Camera2Interop at bind time)
            }

            // Convert YUV to JPEG
            val jpegBytes = yuvToJpeg(imageProxy)

            if (jpegBytes != null) {
                mjpegServer.sendFrame(jpegBytes)
                frameCount++

                // FPS calculation
                val now = System.currentTimeMillis()
                if (now - lastFpsCheck >= 1000) {
                    currentFps = frameCount
                    frameCount = 0
                    lastFpsCheck = now
                    frameRateListener?.invoke(currentFps)

                    val clients = mjpegServer.getClientCount()
                    updateNotification("🟢 بث مباشر | $currentFps fps | $clients متصل")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun yuvToJpeg(imageProxy: ImageProxy): ByteArray? {
        return try {
            // Use CameraX's built-in bitmap conversion via YUV
            val bitmap = imageProxy.toBitmap()
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            bitmap.recycle()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "YUV to JPEG error: ${e.message}")
            null
        }
    }

    fun stopStreaming() {
        isStreaming = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { mjpegServer.stop() } catch (_: Exception) {}
        updateNotification("⏹ توقف البث")
        statusListener?.invoke("stopped")

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopSelf()
        }, 1500)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NightVisionCamera:StreamingWakeLock"
        )
        wakeLock.acquire(10 * 60 * 60 * 1000L)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Night Vision Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "بث كاميرا الرؤية الليلية المباشر"
                lightColor = Color.CYAN
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String, showStop: Boolean = false): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, StreamingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌙 Night Vision Stream")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(mainIntent)
            .setColor(Color.CYAN)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (showStop) {
            builder.addAction(android.R.drawable.ic_delete, "إيقاف البث", stopIntent)
        }
        return builder.build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(content, isStreaming))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { mjpegServer.stop() } catch (_: Exception) {}
        cameraExecutor.shutdown()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        Log.d(TAG, "StreamingService destroyed")
    }
}
