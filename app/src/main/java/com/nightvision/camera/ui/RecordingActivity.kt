package com.nightvision.camera.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nightvision.camera.databinding.ActivityRecordingBinding
import com.nightvision.camera.service.RecordingService
import java.io.File

class RecordingActivity : AppCompatActivity() {

    private lateinit var b: ActivityRecordingBinding
    private var svc: RecordingService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var elapsed = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, binder: IBinder?) {
            svc = (binder as RecordingService.LocalBinder).get()
            bound = true
            svc?.onStatus = { s -> runOnUiThread { handleStatus(s) } }
            if (svc?.isRecording == true) { elapsed = (System.currentTimeMillis() - (svc?.startTime ?: 0)) / 1000; syncUI(true); startTimer() }
        }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null; bound = false }
    }

    private val tick = object : Runnable {
        override fun run() { elapsed++; updateTimer(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityRecordingBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupUI()
        bindService(Intent(this, RecordingService::class.java), conn, BIND_AUTO_CREATE)
    }

    private fun setupUI() {
        b.btnBack.setOnClickListener { finish() }
        b.btnStartStop.setOnClickListener {
            if (svc?.isRecording == true) stopRec() else startRec()
        }
    }

    private fun startRec() {
        val q = if (b.rb1080p.isChecked) "1080p" else "720p"
        val night = b.swNight.isChecked
        startForegroundService(Intent(this, RecordingService::class.java).apply {
            action = RecordingService.START
            putExtra(RecordingService.Q, q)
            putExtra(RecordingService.NM, night)
        })
        Handler(Looper.getMainLooper()).postDelayed({
            bindService(Intent(this, RecordingService::class.java), conn, BIND_AUTO_CREATE)
        }, 300)
        elapsed = 0; syncUI(true); startTimer()
    }

    private fun stopRec() { svc?.stopRec(); syncUI(false); stopTimer() }

    private fun handleStatus(s: String) {
        when {
            s.startsWith("saved:") -> {
                val f = File(s.removePrefix("saved:"))
                b.tvStatus.text = "✅ ${f.name} (${f.length() / 1024 / 1024} MB)"
                syncUI(false); stopTimer()
                Toast.makeText(this, "تم الحفظ: ${f.name}", Toast.LENGTH_LONG).show()
            }
            s.startsWith("error:") -> { b.tvStatus.text = "⚠️ ${s.removePrefix("error:")}"; syncUI(false); stopTimer() }
            s == "recording" -> b.tvStatus.text = "🔴 جارٍ التسجيل في الخلفية"
        }
    }

    private fun syncUI(rec: Boolean) {
        b.btnStartStop.text = if (rec) "⏹ إيقاف التسجيل" else "▶ بدء التسجيل"
        b.btnStartStop.setBackgroundColor(if (rec) 0xFFFF1744.toInt() else 0xFF00E676.toInt())
        b.recDot.visibility = if (rec) android.view.View.VISIBLE else android.view.View.GONE
        if (!rec) b.tvStatus.text = "جاهز"
        b.rb720p.isEnabled = !rec; b.rb1080p.isEnabled = !rec; b.swNight.isEnabled = !rec
    }

    private fun startTimer() { handler.removeCallbacks(tick); handler.post(tick) }
    private fun stopTimer() { handler.removeCallbacks(tick) }
    private fun updateTimer() {
        val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val s = elapsed % 60
        b.tvTimer.text = String.format("%02d:%02d:%02d", h, m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        if (bound) { unbindService(conn); bound = false }
    }
}
