package com.nightvision.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nightvision.camera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSION_REQUEST_CODE = 100

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.INTERNET,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.WAKE_LOCK
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()
        requestBatteryOptimizationExclusion()
        setupUI()
        animateUI()
    }

    private fun setupUI() {
        binding.btnLocalRecording.setOnClickListener {
            if (hasAllPermissions()) {
                startActivity(Intent(this, LocalRecordingActivity::class.java))
            } else {
                checkAndRequestPermissions()
                Toast.makeText(this, "يرجى منح الأذونات المطلوبة", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLiveStreaming.setOnClickListener {
            if (hasAllPermissions()) {
                startActivity(Intent(this, StreamingActivity::class.java))
            } else {
                checkAndRequestPermissions()
                Toast.makeText(this, "يرجى منح الأذونات المطلوبة", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnViewStream.setOnClickListener {
            startActivity(Intent(this, ViewerActivity::class.java))
        }

        binding.btnNightSettings.setOnClickListener {
            startActivity(Intent(this, NightVisionSettingsActivity::class.java))
        }
    }

    private fun animateUI() {
        binding.tvTitle.alpha = 0f
        binding.tvSubtitle.alpha = 0f
        binding.moonIcon.alpha = 0f
        binding.cardLocalRecording.alpha = 0f
        binding.cardLiveStreaming.alpha = 0f
        binding.cardViewStream.alpha = 0f

        binding.moonIcon.animate().alpha(1f).setDuration(600).setStartDelay(100).start()
        binding.tvTitle.animate().alpha(1f).translationYBy(-20f).setDuration(600).setStartDelay(200).start()
        binding.tvSubtitle.animate().alpha(1f).translationYBy(-15f).setDuration(600).setStartDelay(300).start()
        binding.cardLocalRecording.animate().alpha(1f).translationYBy(-20f).setDuration(600).setStartDelay(400).start()
        binding.cardLiveStreaming.animate().alpha(1f).translationYBy(-20f).setDuration(600).setStartDelay(500).start()
        binding.cardViewStream.animate().alpha(1f).translationYBy(-20f).setDuration(600).setStartDelay(600).start()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun requestBatteryOptimizationExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Device may not support this, try fallback
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (ex: Exception) { /* ignore */ }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.filterIndexed { i, _ ->
                grantResults[i] != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "بعض الأذونات مرفوضة: ${denied.joinToString()}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
