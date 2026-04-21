package com.nightvision.camera.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.nightvision.camera.R
import com.nightvision.camera.camera.NightVision
import com.nightvision.camera.streaming.MjpegServer
import com.nightvision.camera.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class StreamingService : LifecycleService() {

    private val TAG = "StreamingService"
    private val CH = "stream_ch"
    private val NID = 1002

    inner class LocalBinder : Binder() { fun get() = this@StreamingService }
    private val binder = LocalBinder()

    private var provider: ProcessCameraProvider? = null
    private val camEx = Executors.newSingleThreadExecutor()
    private lateinit var wl: PowerManager.WakeLock
    val server = MjpegServer(8080)

    var isStreaming = false; private set
    var ip = ""; private set
    var onStatus: ((String) -> Unit)? = null
    var onFps: ((Int) -> Unit)? = null

    private var fCount = 0
    private var fTime = 0L

    companion object {
        const val START = "START_STREAM"
        const val STOP = "STOP_STREAM"
        const val Q = "QUALITY"
        const val NM = "NIGHT"
        const val PORT = 8080
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NVC:StreamWL")
        wl.acquire(10 * 60 * 60 * 1000L)
        ip = NightVision.getDeviceIp(this)
        server.start()
    }

    override fun onBind(i: Intent): IBinder { super.onBind(i); return binder }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        super.onStartCommand(i, flags, id)
        startForeground(NID, buildNote("جارٍ البث"))
        when (i?.action) {
            START -> startStream(i.getStringExtra(Q) ?: "720p", i.getBooleanExtra(NM, true))
            STOP -> stopStream()
        }
        return START_STICKY
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun startStream(q: String, night: Boolean) {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            provider = fut.get()
            val res = if (q == "1080p") Size(1920, 1080) else Size(1280, 720)
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(res)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(camEx) { img -> processFrame(img) }

            try {
                provider?.unbindAll()
                val cam = provider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                if (night && cam != null) NightVision.enable(cam)

                isStreaming = true
                val url = "http://$ip:$PORT"
                updateNote("🟢 بث مباشر | $url  PIN: ${server.pin}")
                onStatus?.invoke("streaming:$url")
            } catch (e: Exception) {
                Log.e(TAG, e.message ?: "")
                onStatus?.invoke("error:${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(img: ImageProxy) {
        try {
            val bmp = img.toBitmap()
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
            bmp.recycle()
            server.sendFrame(out.toByteArray())

            fCount++
            val now = System.currentTimeMillis()
            if (now - fTime >= 1000) {
                onFps?.invoke(fCount)
                fCount = 0
                fTime = now
                updateNote("🟢 بث | ${server.clientCount()} متصل | PIN: ${server.pin}")
            }
        } catch (e: Exception) {
            Log.e(TAG, e.message ?: "")
        } finally {
            img.close()
        }
    }

    fun stopStream() {
        isStreaming = false
        try { provider?.unbindAll() } catch (_: Exception) {}
        updateNote("⏹ توقف البث")
        onStatus?.invoke("stopped")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 1500)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH, "Streaming", NotificationManager.IMPORTANCE_LOW)
            ch.lightColor = Color.CYAN
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNote(txt: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 3,
            Intent(this, StreamingService::class.java).apply { action = STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("🌙 Night Vision Stream")
            .setContentText(txt)
            .setSmallIcon(R.drawable.ic_moon)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_delete, "إيقاف", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNote(txt: String) {
        getSystemService(NotificationManager::class.java).notify(NID, buildNote(txt))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { provider?.unbindAll() } catch (_: Exception) {}
        server.stop()
        camEx.shutdown()
        if (::wl.isInitialized && wl.isHeld) wl.release()
    }
}
