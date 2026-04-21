package com.nightvision.camera.ui

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

    private lateinit var b: ActivityMainBinding
    private val REQ = 100

    private val perms = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.INTERNET,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.WAKE_LOCK
    ).apply {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        requestPerms()
        requestBatteryExclusion()
        setupUI()
        animate()
    }

    private fun setupUI() {
        b.btnRecord.setOnClickListener {
            if (hasPerms()) startActivity(Intent(this, RecordingActivity::class.java))
            else { requestPerms(); toast("امنح الأذونات أولاً") }
        }
        b.btnStream.setOnClickListener {
            if (hasPerms()) startActivity(Intent(this, StreamingActivity::class.java))
            else { requestPerms(); toast("امنح الأذونات أولاً") }
        }
        b.btnViewer.setOnClickListener { startActivity(Intent(this, ViewerActivity::class.java)) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private fun animate() {
        listOf(b.ivMoon, b.tvTitle, b.tvSub, b.cardRecord, b.cardStream, b.cardViewer).forEachIndexed { i, v ->
            v.alpha = 0f
            v.animate().alpha(1f).translationYBy(-15f).setDuration(500).setStartDelay((i * 100).toLong()).start()
        }
    }

    private fun hasPerms() = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPerms() {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ)
    }

    private fun requestBatteryExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
