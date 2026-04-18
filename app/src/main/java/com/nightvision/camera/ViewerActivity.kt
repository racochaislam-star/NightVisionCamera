package com.nightvision.camera

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nightvision.camera.databinding.ActivityViewerBinding
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class ViewerActivity : AppCompatActivity() {

    private val TAG = "ViewerActivity"
    private lateinit var binding: ActivityViewerBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var streamFuture: Future<*>? = null
    private val isStreaming = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameCount = 0
    private var lastFpsTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            stopStream()
            finish()
        }

        binding.btnConnect.setOnClickListener {
            val ip = binding.etServerIp.text.toString().trim()
            val port = binding.etPort.text.toString().trim().ifEmpty { "8080" }
            if (ip.isEmpty()) {
                Toast.makeText(this, "أدخل عنوان IP الكاميرا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startMjpegStream("http://$ip:$port/stream")
        }

        binding.btnDisconnect.setOnClickListener {
            stopStream()
        }

        binding.btnSnapshot.setOnClickListener {
            val ip = binding.etServerIp.text.toString().trim()
            val port = binding.etPort.text.toString().trim().ifEmpty { "8080" }
            takeSnapshot("http://$ip:$port/snapshot")
        }

        binding.btnFullscreen.setOnClickListener {
            binding.ivStream.scaleType = if (binding.ivStream.scaleType == ImageView.ScaleType.FIT_CENTER)
                ImageView.ScaleType.CENTER_CROP
            else ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun startMjpegStream(url: String) {
        stopStream()
        isStreaming.set(true)
        frameCount = 0
        lastFpsTime = System.currentTimeMillis()

        binding.tvStatus.text = "🔄 جارٍ الاتصال..."
        binding.btnConnect.isEnabled = false
        binding.btnDisconnect.isEnabled = true
        binding.progressBar.visibility = android.view.View.VISIBLE

        streamFuture = executor.submit {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("Connection", "keep-alive")
                }

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    mainHandler.post {
                        binding.tvStatus.text = "⚠️ خطأ في الاتصال: $responseCode"
                        onConnectionFailed()
                    }
                    return@submit
                }

                mainHandler.post {
                    binding.tvStatus.text = "🟢 متصل - جارٍ البث"
                    binding.progressBar.visibility = android.view.View.GONE
                }

                val inputStream = connection.inputStream
                parseMjpegStream(inputStream)

            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}")
                mainHandler.post {
                    binding.tvStatus.text = "❌ انقطع الاتصال: ${e.message}"
                    onConnectionFailed()
                }
            }
        }
    }

    private fun parseMjpegStream(inputStream: InputStream) {
        val buffer = ByteArray(65536)
        val frameBuffer = java.io.ByteArrayOutputStream(65536)
        var inFrame = false
        var contentLength = -1

        // MJPEG boundary parser
        val dataStream = inputStream.buffered()

        while (isStreaming.get()) {
            try {
                // Read a line to find boundary/headers
                val line = readLine(dataStream) ?: break

                when {
                    line.startsWith("--frame") || line.startsWith("--") && line.length > 2 -> {
                        // New frame boundary
                        frameBuffer.reset()
                        inFrame = false
                        contentLength = -1
                    }
                    line.startsWith("Content-Length:", ignoreCase = true) -> {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                    line.startsWith("Content-Type: image/jpeg", ignoreCase = true) -> {
                        inFrame = true
                    }
                    line.isEmpty() && inFrame -> {
                        // Empty line after headers - read JPEG data
                        if (contentLength > 0) {
                            val jpegBytes = ByteArray(contentLength)
                            var bytesRead = 0
                            while (bytesRead < contentLength && isStreaming.get()) {
                                val n = dataStream.read(jpegBytes, bytesRead, contentLength - bytesRead)
                                if (n == -1) break
                                bytesRead += n
                            }
                            if (bytesRead == contentLength) {
                                displayFrame(jpegBytes)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isStreaming.get()) Log.e(TAG, "Parse error: ${e.message}")
                break
            }
        }
    }

    private fun readLine(stream: java.io.InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (true) {
            c = stream.read()
            if (c == -1) return null
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun displayFrame(jpegBytes: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return

            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                val fps = frameCount
                frameCount = 0
                lastFpsTime = now
                mainHandler.post {
                    binding.tvFps.text = "$fps fps"
                }
            }

            mainHandler.post {
                binding.ivStream.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Display frame error: ${e.message}")
        }
    }

    private fun takeSnapshot(url: String) {
        executor.submit {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 5000
                }
                val bytes = connection.inputStream.readBytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    // Save to gallery
                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val filename = "NightVision_Snapshot_$timestamp.jpg"
                    val dir = getExternalFilesDir("NightVision/Snapshots") ?: filesDir
                    dir.mkdirs()
                    val file = java.io.File(dir, filename)
                    java.io.FileOutputStream(file).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    mainHandler.post {
                        Toast.makeText(this, "📷 تم حفظ اللقطة: $filename", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "فشل التقاط الصورة", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopStream() {
        isStreaming.set(false)
        streamFuture?.cancel(true)
        streamFuture = null
        mainHandler.post {
            binding.tvStatus.text = "⏹ منفصل"
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
            binding.progressBar.visibility = android.view.View.GONE
        }
    }

    private fun onConnectionFailed() {
        binding.progressBar.visibility = android.view.View.GONE
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
        executor.shutdownNow()
    }
}
