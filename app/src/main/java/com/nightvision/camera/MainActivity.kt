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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkAndRequestPermissions()
        requestBatteryOptimizationExclusion()
        setupUI()
    }
    private fun setupUI() {
        binding.btnLocalRecording.setOnClickListener {
            if (hasAllPermissions()) startActivity(Intent(this, LocalRecordingActivity::class.java))
            else { checkAndRequestPermissions(); Toast.makeText(this,"يرجى منح الأذونات",Toast.LENGTH_SHORT).show() }
        }
        binding.btnLiveStreaming.setOnClickListener {
            if (hasAllPermissions()) startActivity(Intent(this, StreamingActivity::class.java))
            else { checkAndRequestPermissions(); Toast.makeText(this,"يرجى منح الأذونات",Toast.LENGTH_SHORT).show() }
        }
        binding.btnViewStream.setOnClickListener { startActivity(Intent(this, ViewerActivity::class.java)) }
        binding.btnNightSettings.setOnClickListener { startActivity(Intent(this, NightVisionSettingsActivity::class.java)) }
    }
    private fun hasAllPermissions() = requiredPermissions.all { androidx.core.content.ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun checkAndRequestPermissions() {
        val notGranted = requiredPermissions.filter { androidx.core.content.ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (notGranted.isNotEmpty()) ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
    }
    private fun requestBatteryOptimizationExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }) }
                catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (ex: Exception) {} }
            }
        }
    }
}