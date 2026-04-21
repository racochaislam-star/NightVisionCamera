package com.nightvision.camera

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
import com.nightvision.camera.camera.NightVisionManager
import com.nightvision.camera.databinding.ActivityStreamingBinding
import com.nightvision.camera.service.StreamingService

class StreamingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamingBinding
    private var streamingService: StreamingService? = null
    private var isBound = false
    private var isBindingPending = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0L
    private lateinit var nightVisionManager: NightVisionManager

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            isBound = true
            isBindingPending = false

            streamingService?.statusListener = { status -> runOnUiThread { handleStatus(status) } }
            streamingService?.frameRateListener = { fps ->
                runOnUiThread { binding.tvFps.text = "$fps fps" }
            }

            if (streamingService?.isStreaming == true) {
                val url = "http://${streamingService?.serverIp}:${StreamingService.STREAM_PORT_DEFAULT}/stream"
                updateUI(true, url)
                startTimer()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            isBound = false
            isBindingPending = false
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            val h = elapsedSeconds / 3600
            val m = (elapsedSeconds % 3600) / 60
            val s = elapsedSeconds % 60
            binding.tvTimer.text = String.format("%02d:%02d:%02d", h, m, s)
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        nightVisionManager = NightVisionManager(this)
        setupUI()
        bindToService()
    }

    private fun bindToService() {
        if (isBound || isBindingPending) return
        isBindingPending = true
        bindService(Intent(this, StreamingService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnStartStop.setOnClickListener {
            if (streamingService?.isStreaming == true) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        binding.btnOpenViewer.setOnClickListener {
            startActivity(Intent(this, ViewerActivity::class.java))
        }

        binding.btnCopyUrl.setOnClickListener {
            val url = binding.tvStreamUrl.text.toString()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Stream URL", url))
            Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
        }

        binding.switchNightVision.setOnCheckedChangeListener { _, isChecked ->
            binding.tvNightStatus.text = if (isChecked) "🌙 رؤية ليلية" else "☀️ وضع عادي"
        }

        val ip = nightVisionManager.getDeviceIpAddress(this)
        binding.tvDeviceIp.text = "IP هذا الجهاز: $ip"
        binding.tvStreamUrl.text = "http://$ip:8080/stream"
    }

    private fun startStreaming() {
        val quality = if (binding.rb1080p.isChecked) "1080p" else "720p"
        val nightMode = binding.switchNightVision.isChecked

        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_QUALITY, quality)
            putExtra(StreamingService.EXTRA_NIGHT_MODE, nightMode)
        }
        startForegroundService(intent)

        elapsedSeconds = 0
        val ip = nightVisionManager.getDeviceIpAddress(this)
        updateUI(true, "http://$ip:8080/stream")
        startTimer()
    }

    private fun stopStreaming() {
        streamingService?.stopStreaming()
        updateUI(false, "")
        stopTimer()
    }

    private fun handleStatus(status: String) {
        when {
            status.startsWith("streaming:") -> {
                val url = status.removePrefix("streaming:")
                binding.tvStreamUrl.text = url
                binding.tvStatus.text = "🟢 البث جارٍ - شارك الرابط مع جهاز العرض"
            }
            status == "stopped" -> {
                binding.tvStatus.text = "⏹ توقف البث"
                updateUI(false, "")
                stopTimer()
            }
            status.startsWith("error:") -> {
                binding.tvStatus.text = "⚠️ خطأ: ${status.removePrefix("error:")}"
                updateUI(false, "")
                stopTimer()
            }
        }
    }

    private fun updateUI(isStreaming: Boolean, url: String) {
        if (isStreaming) {
            binding.btnStartStop.text = "⏹ إيقاف البث"
            binding.btnStartStop.setBackgroundResource(R.drawable.btn_stop_background)
            binding.streamingIndicator.visibility = android.view.View.VISIBLE
            binding.tvStreamUrl.text = url
            binding.tvStatus.text = "🟢 البث جارٍ"
            binding.cardStreamInfo.visibility = android.view.View.VISIBLE
            binding.btnOpenViewer.isEnabled = true
            binding.btnCopyUrl.isEnabled = true
        } else {
            binding.btnStartStop.text = "▶ بدء البث"
            binding.btnStartStop.setBackgroundResource(R.drawable.btn_start_background)
            binding.streamingIndicator.visibility = android.view.View.GONE
            binding.tvStatus.text = "جاهز للبث"
            binding.btnOpenViewer.isEnabled = false
            binding.btnCopyUrl.isEnabled = false
        }
    }

    private fun startTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
    }

    private fun stopTimer() {
        timerHandler.removeCallbacks(timerRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (!isBound && !isBindingPending) bindToService()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        if (isBound) {
            streamingService?.statusListener = null
            streamingService?.frameRateListener = null
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
