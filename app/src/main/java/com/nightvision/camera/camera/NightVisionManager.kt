package com.nightvision.camera.camera

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.ExposureState

object NightVisionPrefs {
    const val PREFS_NAME = "night_vision_prefs"
    const val KEY_AUTO_MODE = "auto_mode"
    const val KEY_FLASH_LEVEL = "flash_level"
    const val KEY_LIGHT_THRESHOLD = "light_threshold"
    const val KEY_NIGHT_ENABLED = "night_enabled"
    const val KEY_ISO_LEVEL = "iso_level"
    const val KEY_EXPOSURE_NS = "exposure_ns"

    const val FLASH_OFF = 0
    const val FLASH_WEAK = 1
    const val FLASH_MEDIUM = 2
    const val FLASH_STRONG = 3

    const val ISO_LOW = 800
    const val ISO_MEDIUM = 1600
    const val ISO_HIGH = 3200
    const val ISO_MAX = 6400

    const val EXPOSURE_SHORT = 33_000_000L  // 33ms
    const val EXPOSURE_MEDIUM = 66_000_000L // 66ms
    const val EXPOSURE_LONG = 100_000_000L  // 100ms
}

class NightVisionManager(private val context: Context) {

    private val TAG = "NightVisionManager"
    private val prefs = context.getSharedPreferences(NightVisionPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    var isNightVisionActive = false
        private set

    var isAutoMode: Boolean
        get() = prefs.getBoolean(NightVisionPrefs.KEY_AUTO_MODE, true)
        set(value) = prefs.edit().putBoolean(NightVisionPrefs.KEY_AUTO_MODE, value).apply()

    var flashLevel: Int
        get() = prefs.getInt(NightVisionPrefs.KEY_FLASH_LEVEL, NightVisionPrefs.FLASH_OFF)
        set(value) = prefs.edit().putInt(NightVisionPrefs.KEY_FLASH_LEVEL, value).apply()

    var lightThreshold: Int
        get() = prefs.getInt(NightVisionPrefs.KEY_LIGHT_THRESHOLD, 60)
        set(value) = prefs.edit().putInt(NightVisionPrefs.KEY_LIGHT_THRESHOLD, value).apply()

    var isoLevel: Int
        get() = prefs.getInt(NightVisionPrefs.KEY_ISO_LEVEL, NightVisionPrefs.ISO_HIGH)
        set(value) = prefs.edit().putInt(NightVisionPrefs.KEY_ISO_LEVEL, value).apply()

    var exposureNs: Long
        get() = prefs.getLong(NightVisionPrefs.KEY_EXPOSURE_NS, NightVisionPrefs.EXPOSURE_MEDIUM)
        set(value) = prefs.edit().putLong(NightVisionPrefs.KEY_EXPOSURE_NS, value).apply()

    /**
     * Enable night vision mode on the camera using Camera2Interop
     * Sets high ISO, long exposure, noise reduction, and shading correction
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun enableNightVision(camera: Camera) {
        try {
            val camera2Control = Camera2CameraControl.from(camera.cameraControl)

            val optionsBuilder = CaptureRequestOptions.Builder()
                // Disable auto exposure - we control manually
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                // High ISO sensitivity for low light
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    isoLevel
                )
                // Longer exposure time for more light
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    exposureNs
                )
                // Maximum noise reduction
                .setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
                // High quality shading correction
                .setCaptureRequestOption(
                    CaptureRequest.SHADING_MODE,
                    CameraMetadata.SHADING_MODE_HIGH_QUALITY
                )
                // Disable flash auto mode
                .setCaptureRequestOption(
                    CaptureRequest.FLASH_MODE,
                    CameraMetadata.FLASH_MODE_OFF
                )
                // Optimize for low light
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CameraMetadata.CONTROL_AWB_MODE_AUTO
                )
                // Edge enhancement for sharper image
                .setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CameraMetadata.EDGE_MODE_HIGH_QUALITY
                )

            camera2Control.addCaptureRequestOptions(optionsBuilder.build())
            isNightVisionActive = true
            Log.d(TAG, "Night vision enabled: ISO=$isoLevel, Exposure=${exposureNs}ns")

            // Apply flash/torch if needed
            applyFlash(camera)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable night vision via Camera2: ${e.message}")
            // Fallback: use CameraX exposure compensation
            enableNightVisionFallback(camera)
        }
    }

    /**
     * Fallback night vision using CameraX exposure compensation (no Camera2 interop)
     */
    private fun enableNightVisionFallback(camera: Camera) {
        try {
            val exposureState: ExposureState = camera.cameraInfo.exposureState
            if (exposureState.isExposureCompensationSupported) {
                val maxExposure = exposureState.exposureCompensationRange.upper
                camera.cameraControl.setExposureCompensationIndex(maxExposure)
                Log.d(TAG, "Night vision fallback: max exposure compensation = $maxExposure")
            }
            isNightVisionActive = true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback night vision failed: ${e.message}")
        }
    }

    /**
     * Disable night vision - restore auto settings
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun disableNightVision(camera: Camera) {
        try {
            val camera2Control = Camera2CameraControl.from(camera.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON
                )
                .setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraMetadata.NOISE_REDUCTION_MODE_FAST
                )
                .setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CameraMetadata.EDGE_MODE_FAST
                )
                .build()
            camera2Control.addCaptureRequestOptions(options)
            camera.cameraControl.enableTorch(false)
            camera.cameraControl.setExposureCompensationIndex(0)
            isNightVisionActive = false
            Log.d(TAG, "Night vision disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable night vision: ${e.message}")
        }
    }

    /**
     * Control torch/flash LED based on flashLevel setting
     */
    private fun applyFlash(camera: Camera) {
        when (flashLevel) {
            NightVisionPrefs.FLASH_OFF -> {
                camera.cameraControl.enableTorch(false)
            }
            NightVisionPrefs.FLASH_WEAK,
            NightVisionPrefs.FLASH_MEDIUM,
            NightVisionPrefs.FLASH_STRONG -> {
                camera.cameraControl.enableTorch(true)
            }
        }
    }

    /**
     * Analyze frame brightness to decide if night vision is needed (auto mode)
     * Returns true if night vision should be enabled
     */
    fun analyzeFrameBrightness(yuvBytes: ByteArray, width: Int, height: Int): Boolean {
        // Sample every 8th pixel for performance
        var total = 0L
        var count = 0
        val step = 8
        val yPlaneSize = width * height
        val actualSize = minOf(yuvBytes.size, yPlaneSize)

        var i = 0
        while (i < actualSize) {
            total += (yuvBytes[i].toInt() and 0xFF)
            count++
            i += step
        }

        val avgBrightness = if (count > 0) (total / count).toInt() else 128
        Log.v(TAG, "Frame brightness: $avgBrightness (threshold: $lightThreshold)")
        return avgBrightness < lightThreshold
    }

    /**
     * Get WiFi IP address of this device
     */
    fun getDeviceIpAddress(context: Context): String {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            return android.text.format.Formatter.formatIpAddress(ipInt)
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }
}
