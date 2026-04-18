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
import com.nightvision.camera.databinding.ActivityLocalRecordingBinding
import com.nightvision.camera.service.RecordingService
import java.io.File

class LocalRecordingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalRecordingBinding
    private var recordingService: RecordingService? = null
    private var isBound = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true

            recordingService?.statusListener = { status ->
                runOnUiThread { handleStatus(status) }
            }

            // Sync UI if service is already recording
            if (recordingService?.isRecording == true) {
                val elapsed = (System.currentTimeMillis() - (recordingService?.recordingStartTime ?: 0)) / 1000
                elapsedSeconds = elapsed
                updateUI(true)
                startTimer()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            updateTimer()
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityLocalRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        bindToService()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        // Quality selector
        binding.rgQuality.setOnCheckedChangeListener { _, checkedId ->
            binding.tvSelectedQuality.text = if (checkedId == R.id.rb1080p) "1080p Full HD" else "720p HD"
        }

        // Night mode toggle
        binding.switchNightVision.setOnCheckedChangeListener { _, isChecked ->
            binding.tvNightStatus.text = if (isChecked) "🌙 وضع الليل مفعّل" else "☀️ وضع النهار"
            binding.ivMoonIndicator.alpha = if (isChecked) 1f else 0.3f
        }

        // Main start/stop button
        binding.btnStartStop.setOnClickListener {
            if (recordingService?.isRecording == true) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        // Night settings shortcut
        binding.btnNightSettings.setOnClickListener {
            startActivity(Intent(this, NightVisionSettingsActivity::class.java))
        }
    }

    private fun startRecording() {
        val quality = if (binding.rb1080p.isChecked) "1080p" else "720p"
        val nightMode = binding.switchNightVision.isChecked

        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_QUALITY, quality)
            putExtra(RecordingService.EXTRA_NIGHT_MODE, nightMode)
        }
        startForegroundService(intent)

        // Bind after slight delay to ensure service started
        Handler(Looper.getMainLooper()).postDelayed({ bindToService() }, 300)

        elapsedSeconds = 0
        updateUI(true)
        startTimer()
        Toast.makeText(this, "بدأ التسجيل في الخلفية", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        recordingService?.stopRecording()
        updateUI(false)
        stopTimer()
        Toast.makeText(this, "جارٍ حفظ الفيديو...", Toast.LENGTH_SHORT).show()
    }

    private fun handleStatus(status: String) {
        when {
            status.startsWith("saved:") -> {
                val path = status.removePrefix("saved:")
                val file = File(path)
                binding.tvStatus.text = "✅ تم الحفظ: ${file.name} (${file.length() / 1024 / 1024} MB)"
                updateUI(false)
                stopTimer()
                Toast.makeText(this, "تم حفظ الفيديو: ${file.name}", Toast.LENGTH_LONG).show()
            }
            status.startsWith("error:") -> {
                binding.tvStatus.text = "⚠️ خطأ: ${status.removePrefix("error:")}"
                updateUI(false)
                stopTimer()
            }
            status == "recording" -> {
                binding.tvStatus.text = "🔴 جارٍ التسجيل في الخلفية"
            }
        }
    }

    private fun updateUI(isRecording: Boolean) {
        if (isRecording) {
            binding.btnStartStop.text = "⏹ إيقاف التسجيل"
            binding.btnStartStop.setBackgroundResource(R.drawable.btn_stop_background)
            binding.recordingIndicator.visibility = android.view.View.VISIBLE
            binding.tvStatus.text = "🔴 جارٍ التسجيل..."
            binding.rgQuality.isEnabled = false
            binding.rb720p.isEnabled = false
            binding.rb1080p.isEnabled = false
            binding.switchNightVision.isEnabled = false
            startRecordingAnimation()
        } else {
            binding.btnStartStop.text = "▶ بدء التسجيل"
            binding.btnStartStop.setBackgroundResource(R.drawable.btn_start_background)
            binding.recordingIndicator.visibility = android.view.View.GONE
            binding.rgQuality.isEnabled = true
            binding.rb720p.isEnabled = true
            binding.rb1080p.isEnabled = true
            binding.switchNightVision.isEnabled = true
            stopRecordingAnimation()
        }
    }

    private fun startRecordingAnimation() {
        binding.recordingIndicator.animate()
            .alpha(0f).setDuration(700)
            .withEndAction {
                binding.recordingIndicator.animate()
                    .alpha(1f).setDuration(700)
                    .withEndAction { if (recordingService?.isRecording == true) startRecordingAnimation() }
                    .start()
            }.start()
    }

    private fun stopRecordingAnimation() {
        binding.recordingIndicator.animate().cancel()
        binding.recordingIndicator.alpha = 1f
    }

    private fun startTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
    }

    private fun stopTimer() {
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun updateTimer() {
        val h = elapsedSeconds / 3600
        val m = (elapsedSeconds % 3600) / 60
        val s = elapsedSeconds % 60
        binding.tvTimer.text = String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun bindToService() {
        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (!isBound) bindToService()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
