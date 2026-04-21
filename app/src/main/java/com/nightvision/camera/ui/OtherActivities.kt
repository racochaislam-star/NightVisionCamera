package com.nightvision.camera.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nightvision.camera.camera.NightVision
import com.nightvision.camera.databinding.ActivityStreamingBinding
import com.nightvision.camera.databinding.ActivityViewerBinding
import com.nightvision.camera.databinding.ActivitySettingsBinding
import com.nightvision.camera.service.StreamingService
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

// ─────────────────────────────────────────────
// STREAMING ACTIVITY
// ─────────────────────────────────────────────
class StreamingActivity : AppCompatActivity() {

    private lateinit var b: ActivityStreamingBinding
    private var svc: StreamingService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var elapsed = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, binder: IBinder?) {
            svc = (binder as StreamingService.LocalBinder).get()
            bound = true
            svc?.onStatus = { s -> runOnUiThread { handleStatus(s) } }
            svc?.onFps = { fps -> runOnUiThread { b.tvFps.text = "$fps fps" } }
            if (svc?.isStreaming == true) { syncUI(true, "http://${svc?.ip}:${StreamingService.PORT}"); startTimer() }
        }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null; bound = false }
    }

    private val tick = object : Runnable {
        override fun run() {
            elapsed++
            val h = elapsed/3600; val m=(elapsed%3600)/60; val s=elapsed%60
            b.tvTimer.text = String.format("%02d:%02d:%02d",h,m,s)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(b.root)
        val ip = NightVision.getDeviceIp(this)
        b.tvIp.text = "IP: $ip  |  المنفذ: 8080"
        b.btnBack.setOnClickListener { finish() }
        b.btnStartStop.setOnClickListener { if (svc?.isStreaming == true) stopStream() else startStream() }
        b.btnOpenViewer.setOnClickListener { startActivity(Intent(this, ViewerActivity::class.java)) }
        b.btnCopyUrl.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", b.tvUrl.text))
            Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
        }
        b.btnResetPin.setOnClickListener {
            val newPin = svc?.server?.resetPin() ?: "----"
            b.tvPin.text = "PIN: $newPin"
            Toast.makeText(this, "PIN الجديد: $newPin", Toast.LENGTH_LONG).show()
        }
        bindService(Intent(this, StreamingService::class.java), conn, BIND_AUTO_CREATE)
    }

    private fun startStream() {
        val q = if (b.rb1080p.isChecked) "1080p" else "720p"
        startForegroundService(Intent(this, StreamingService::class.java).apply {
            action = StreamingService.START
            putExtra(StreamingService.Q, q)
            putExtra(StreamingService.NM, b.swNight.isChecked)
        })
        Handler(Looper.getMainLooper()).postDelayed({
            bindService(Intent(this, StreamingService::class.java), conn, BIND_AUTO_CREATE)
        }, 400)
        elapsed = 0; startTimer()
    }

    private fun stopStream() { svc?.stopStream(); syncUI(false, ""); stopTimer() }

    private fun handleStatus(s: String) {
        when {
            s.startsWith("streaming:") -> {
                val url = s.removePrefix("streaming:")
                b.tvUrl.text = url
                b.tvPin.text = "PIN: ${svc?.server?.pin}"
                syncUI(true, url)
            }
            s == "stopped" -> { syncUI(false, ""); stopTimer(); elapsed = 0 }
            s.startsWith("error:") -> { b.tvStatus.text = "⚠️ ${s.removePrefix("error:")}"; syncUI(false, "") }
        }
    }

    private fun syncUI(on: Boolean, url: String) {
        b.btnStartStop.text = if (on) "⏹ إيقاف البث" else "▶ بدء البث"
        b.btnStartStop.setBackgroundColor(if (on) 0xFFFF1744.toInt() else 0xFF40C4FF.toInt())
        b.tvStatus.text = if (on) "🟢 بث جارٍ" else "جاهز"
        b.tvUrl.text = if (on) url else "http://..."
        b.btnResetPin.isEnabled = on
        b.btnCopyUrl.isEnabled = on
        b.btnOpenViewer.isEnabled = on
    }

    private fun startTimer() { handler.removeCallbacks(tick); handler.post(tick) }
    private fun stopTimer() { handler.removeCallbacks(tick) }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        if (bound) { unbindService(conn); bound = false }
    }
}

// ─────────────────────────────────────────────
// VIEWER ACTIVITY
// ─────────────────────────────────────────────
class ViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityViewerBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var future: Future<*>? = null
    private val streaming = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var fps = 0; private var fpsTime = 0L; private var fCount = 0

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { stopStream(); finish() }
        b.btnConnect.setOnClickListener {
            val ip = b.etIp.text.toString().trim()
            val pin = b.etPin.text.toString().trim()
            if (ip.isEmpty()) { Toast.makeText(this, "أدخل IP الكاميرا", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startMjpeg("http://$ip:8080/stream?code=$pin")
        }
        b.btnDisconnect.setOnClickListener { stopStream() }
    }

    private fun startMjpeg(url: String) {
        stopStream()
        streaming.set(true)
        b.tvStatus.text = "🔄 جارٍ الاتصال..."
        b.btnConnect.isEnabled = false; b.btnDisconnect.isEnabled = true
        b.progress.visibility = android.view.View.VISIBLE
        future = executor.submit {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 10000
                }
                if (conn.responseCode != 200) {
                    handler.post { b.tvStatus.text = "❌ كود خاطئ أو البث متوقف"; reset() }
                    return@submit
                }
                handler.post { b.tvStatus.text = "🟢 متصل"; b.progress.visibility = android.view.View.GONE }
                parseMjpeg(conn.inputStream)
            } catch (e: Exception) {
                handler.post { b.tvStatus.text = "❌ ${e.message}"; reset() }
            }
        }
    }

    private fun parseMjpeg(input: InputStream) {
        val stream = input.buffered()
        while (streaming.get()) {
            try {
                val line = readLine(stream) ?: break
                if (line.isEmpty()) continue
                if (!line.startsWith("Content-Length:", ignoreCase = true)) continue
                val len = line.substringAfter(":").trim().toIntOrNull() ?: continue
                readLine(stream) // blank line
                val jpeg = ByteArray(len)
                var read = 0
                while (read < len && streaming.get()) {
                    val n = stream.read(jpeg, read, len - read)
                    if (n == -1) break
                    read += n
                }
                if (read == len) {
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, len) ?: continue
                    fCount++
                    val now = System.currentTimeMillis()
                    if (now - fpsTime >= 1000) {
                        fps = fCount; fCount = 0; fpsTime = now
                        handler.post { b.tvFps.text = "$fps fps" }
                    }
                    handler.post { b.ivStream.setImageBitmap(bmp) }
                }
            } catch (e: Exception) { if (streaming.get()) handler.post { b.tvStatus.text = "⚠️ ${e.message}"; reset() }; break }
        }
    }

    private fun readLine(s: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = s.read()
            if (c == -1) return null
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun stopStream() {
        streaming.set(false)
        future?.cancel(true)
        future = null
        handler.post { b.tvStatus.text = "⏹ منفصل"; reset() }
    }

    private fun reset() {
        b.btnConnect.isEnabled = true; b.btnDisconnect.isEnabled = false
        b.progress.visibility = android.view.View.GONE
    }

    override fun onDestroy() { super.onDestroy(); stopStream(); executor.shutdownNow() }
}

// ─────────────────────────────────────────────
// SETTINGS ACTIVITY
// ─────────────────────────────────────────────
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.tvVersion.text = "Night Vision Camera v2.0\nRealme UI · Android 10+"
    }
}
