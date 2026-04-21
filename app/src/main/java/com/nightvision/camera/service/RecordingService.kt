package com.nightvision.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.nightvision.camera.MainActivity
import com.nightvision.camera.R
import com.nightvision.camera.camera.NightVisionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RecordingService : LifecycleService() {

    private val TAG = "RecordingService"
    private val CHANNEL_ID = "recording_channel"
    private val NOTIFICATION_ID = 1001

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeRecording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var nightVisionManager: NightVisionManager

    @Volatile
    var isRecording = false
        private set
    var recordingStartTime = 0L
        private set
    var outputFilePath = ""
        private set
    var statusListener: ((String) -> Unit)? = null

    companion object {
        const val ACTION_START = "ACTION_START_RECORDING"
        const val ACTION_STOP = "ACTION_STOP_RECORDING"
        const val EXTRA_QUALITY = "EXTRA_QUALITY"
        const val EXTRA_NIGHT_MODE = "EXTRA_NIGHT_MODE"
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        nightVisionManager = NightVisionManager(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(NOTIFICATION_ID, buildNotification("جارٍ التسجيل...", true))

        when (intent?.action) {
            ACTION_START -> {
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "720p"
                val nightMode = intent.getBooleanExtra(EXTRA_NIGHT_MODE, true)
                startRecording(quality, nightMode)
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_STICKY
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun startRecording(quality: String, nightMode: Boolean) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Quality selector
            val qualitySelector = when (quality) {
                "1080p" -> QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
                else -> QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            }

            // Preview (invisible - required for some devices)
            val previewBuilder = Preview.Builder()

            // Apply Camera2 night vision interop
            if (nightMode) {
                val camera2Ext = Camera2Interop.Extender(previewBuilder)
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

            val preview = previewBuilder.build()

            // Recorder
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setExecutor(cameraExecutor)
                .build()

            val videoCapture = VideoCapture.withOutput(recorder)

            // Output file
            val outputDir = getExternalFilesDir("NightVision") ?: filesDir
            outputDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFile = File(outputDir, "NightVision_${timestamp}.mp4")
            outputFilePath = outputFile.absolutePath

            val outputOptions = FileOutputOptions.Builder(outputFile).build()

            try {
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )

                // Apply night vision after binding
                if (nightMode && camera != null) {
                    nightVisionManager.enableNightVision(camera)
                    if (nightVisionManager.flashLevel != 0) {
                        camera.cameraControl.enableTorch(true)
                    }
                }

                // Start recording - NO AUDIO
                activeRecording = videoCapture.output
                    .prepareRecording(this, outputOptions)
                    .start(cameraExecutor) { event ->
                        handleRecordingEvent(event)
                    }

                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                updateNotification("🔴 تسجيل جارٍ | ${outputFile.name}")
                statusListener?.invoke("recording")
                Log.i(TAG, "Recording started: $outputFilePath")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}")
                statusListener?.invoke("error: ${e.message}")
                stopSelf()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleRecordingEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.d(TAG, "VideoRecordEvent.Start")
            }
            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                val durationMs = stats.recordedDurationNanos / 1_000_000
                val fileSizeMb = stats.numBytesRecorded / (1024 * 1024)
                Log.v(TAG, "Recording: ${durationMs / 1000}s, ${fileSizeMb}MB")
                updateNotification("🔴 تسجيل: ${durationMs / 1000}ث | ${fileSizeMb}MB")
            }
            is VideoRecordEvent.Finalize -> {
                isRecording = false
                if (!event.hasError()) {
                    Log.i(TAG, "Recording saved: $outputFilePath")
                    updateNotification("✅ اكتمل التسجيل")
                    statusListener?.invoke("saved:$outputFilePath")
                    android.media.MediaScannerConnection.scanFile(this@RecordingService, arrayOf(outputFilePath), arrayOf("video/mp4"), null)
                } else {
                    Log.e(TAG, "Recording error: ${event.error}")
                    updateNotification("⚠️ خطأ في التسجيل")
                    statusListener?.invoke("error:${event.error}")
                }
            }
            else -> {}
        }
    }

    fun stopRecording() {
        try {
            activeRecording?.stop()
            activeRecording = null
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording error: ${e.message}")
        }

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) { /* ignore */ }

        updateNotification("⏳ جارٍ حفظ الفيديو...")

        // Delay then stop service to allow Finalize event to complete
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopSelf()
        }, 2000)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NightVisionCamera:RecordingWakeLock"
        )
        wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 hours max
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Night Vision Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "تسجيل كاميرا الرؤية الليلية"
                lightColor = Color.GREEN
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String, showStop: Boolean = false): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌙 Night Vision Camera")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(mainIntent)
            .setColor(Color.GREEN)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (showStop) {
            builder.addAction(android.R.drawable.ic_media_pause, "إيقاف", stopIntent)
        }

        return builder.build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content, isRecording))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { activeRecording?.stop() } catch (_: Exception) {}
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraExecutor.shutdown()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        Log.d(TAG, "RecordingService destroyed")
    }
}
