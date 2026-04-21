package com.nightvision.camera.camera

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
object NightVision {

    private const val TAG = "NightVision"

    fun enable(camera: Camera) {
        try {
            val ctrl = Camera2CameraControl.from(camera.cameraControl)
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, 3200)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 66_000_000L)
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                .build()
            ctrl.addCaptureRequestOptions(opts)
            Log.i(TAG, "Night vision enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.message}")
            try {
                val state = camera.cameraInfo.exposureState
                if (state.isExposureCompensationSupported) {
                    camera.cameraControl.setExposureCompensationIndex(state.exposureCompensationRange.upper)
                }
            } catch (ex: Exception) { Log.e(TAG, "Fallback failed: ${ex.message}") }
        }
    }

    fun disable(camera: Camera) {
        try {
            val ctrl = Camera2CameraControl.from(camera.cameraControl)
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                .build()
            ctrl.addCaptureRequestOptions(opts)
            camera.cameraControl.enableTorch(false)
        } catch (e: Exception) { Log.e(TAG, "Disable failed: ${e.message}") }
    }

    fun getDeviceIp(context: Context): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            android.text.format.Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        } catch (e: Exception) { "127.0.0.1" }
    }
}
