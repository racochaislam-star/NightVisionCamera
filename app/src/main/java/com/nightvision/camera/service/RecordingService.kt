package com.nightvision.camera.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.nightvision.camera.R
import com.nightvision.camera.camera.NightVision
import com.nightvision.camera.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : LifecycleService() {

    private val TAG = "RecordingService"
    private val CH = "rec_ch"
    private val NID = 1001

    inner class LocalBinder : Binder() { fun get() = this@RecordingService }
    private val binder = LocalBinder()

    private var provider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private lateinit var wl: PowerManager.WakeLock

    var isRecording = false; private set
    var startTime = 0L; private set
    var filePath = ""; private set
    var onStatus: ((String) -> Unit)? = null

    companion object {
        const val START = "START_REC"
        const val STOP = "STOP_REC"
        const val Q = "QUALITY"
        const val NM = "NIGHT"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NVC:RecWL")
        wl.acquire(10 * 60 * 60 * 1000L)
    }

    override fun onBind(i: Intent): IBinder { super.onBind(i); return binder }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        super.onStartCommand(i, flags, id)
        startForeground(NID, buildNote("جاهز للتسجيل"))
        when (i?.action) {
            START -> startRec(i.getStringExtra(Q) ?: "720p", i.getBooleanExtra(NM, true))
            STOP -> stopRec()
        }
        return START_STICKY
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun startRec(q: String, night: Boolean) {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            provider = fut.get()
            val qs = when (q) {
                "1080p" -> QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
                else -> QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            }
            val recorder = Recorder.Builder().setQualitySelector(qs).build()
            val vc = VideoCapture.withOutput(recorder)
            val preview = Preview.Builder().build()

            val dir = getExternalFilesDir("NightVision") ?: filesDir
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "NV_$ts.mp4")
            filePath = file.absolutePath

            try {
                provider?.unbindAll()
                val cam = provider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
                if (night && cam != null) NightVision.enable(cam)

                recording = vc.output
                    .prepareRecording(this, FileOutputOptions.Builder(file).build())
                    .start(ContextCompat.getMainExecutor(this)) { e ->
                        when (e) {
                            is VideoRecordEvent.Start -> {
                                isRecording = true
                                startTime = System.currentTimeMillis()
                                updateNote("🔴 تسجيل جارٍ")
                                onStatus?.invoke("recording")
                            }
                            is VideoRecordEvent.Finalize -> {
                                isRecording = false
                                if (!e.hasError()) {
                                    scanFile(filePath)
                                    onStatus?.invoke("saved:$filePath")
                                } else {
                                    Log.e(TAG, "Record error: ${e.error}")
                                    onStatus?.invoke("error:${e.error}")
                                }
                                stopSelf()
                            }
                            else -> {}
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, e.message ?: "")
                onStatus?.invoke("error:${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scanFile(path: String) {
        android.media.MediaScannerConnection.scanFile(
            this, arrayOf(path), arrayOf("video/mp4"), null
        )
    }

    fun stopRec() {
        try { recording?.stop(); recording = null } catch (_: Exception) {}
        try { provider?.unbindAll() } catch (_: Exception) {}
        isRecording = false
        updateNote("✅ اكتمل")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 2000)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH, "Recording", NotificationManager.IMPORTANCE_LOW)
            ch.lightColor = Color.GREEN
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNote(txt: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, RecordingService::class.java).apply { action = STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("🌙 Night Vision Camera")
            .setContentText(txt)
            .setSmallIcon(R.drawable.ic_moon)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNote(txt: String) {
        getSystemService(NotificationManager::class.java).notify(NID, buildNote(txt))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recording?.stop() } catch (_: Exception) {}
        try { provider?.unbindAll() } catch (_: Exception) {}
        if (::wl.isInitialized && wl.isHeld) wl.release()
    }
}
