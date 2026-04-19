package com.nightvision.camera.camera
import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
object NightVisionPrefs {
    const val PREFS_NAME="night_vision_prefs"
    const val KEY_AUTO_MODE="auto_mode"
    const val KEY_FLASH_LEVEL="flash_level"
    const val KEY_LIGHT_THRESHOLD="light_threshold"
    const val KEY_ISO_LEVEL="iso_level"
    const val KEY_EXPOSURE_NS="exposure_ns"
    const val FLASH_OFF=0;const val FLASH_WEAK=1;const val FLASH_MEDIUM=2;const val FLASH_STRONG=3
    const val ISO_LOW=800;const val ISO_MEDIUM=1600;const val ISO_HIGH=3200;const val ISO_MAX=6400
    const val EXPOSURE_SHORT=33_000_000L;const val EXPOSURE_MEDIUM=66_000_000L;const val EXPOSURE_LONG=100_000_000L
}
class NightVisionManager(private val context:Context){
    private val prefs=context.getSharedPreferences(NightVisionPrefs.PREFS_NAME,Context.MODE_PRIVATE)
    var isNightVisionActive=false;private set
    var isAutoMode:Boolean get()=prefs.getBoolean(NightVisionPrefs.KEY_AUTO_MODE,true);set(v)=prefs.edit().putBoolean(NightVisionPrefs.KEY_AUTO_MODE,v).apply()
    var flashLevel:Int get()=prefs.getInt(NightVisionPrefs.KEY_FLASH_LEVEL,0);set(v)=prefs.edit().putInt(NightVisionPrefs.KEY_FLASH_LEVEL,v).apply()
    var lightThreshold:Int get()=prefs.getInt(NightVisionPrefs.KEY_LIGHT_THRESHOLD,60);set(v)=prefs.edit().putInt(NightVisionPrefs.KEY_LIGHT_THRESHOLD,v).apply()
    var isoLevel:Int get()=prefs.getInt(NightVisionPrefs.KEY_ISO_LEVEL,NightVisionPrefs.ISO_HIGH);set(v)=prefs.edit().putInt(NightVisionPrefs.KEY_ISO_LEVEL,v).apply()
    var exposureNs:Long get()=prefs.getLong(NightVisionPrefs.KEY_EXPOSURE_NS,NightVisionPrefs.EXPOSURE_MEDIUM);set(v)=prefs.edit().putLong(NightVisionPrefs.KEY_EXPOSURE_NS,v).apply()
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun enableNightVision(camera:Camera){
        try{
            val c=Camera2CameraControl.from(camera.cameraControl)
            val o=CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,CameraMetadata.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,isoLevel)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME,exposureNs)
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE,CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE,CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                .build()
            c.addCaptureRequestOptions(o)
            isNightVisionActive=true
            if(flashLevel!=0)camera.cameraControl.enableTorch(true)
        }catch(e:Exception){Log.e("NVM",e.message?:"")}
    }
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun disableNightVision(camera:Camera){
        try{
            Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(
                CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,CameraMetadata.CONTROL_AE_MODE_ON).build())
            camera.cameraControl.enableTorch(false)
            isNightVisionActive=false
        }catch(e:Exception){}
    }
    fun getDeviceIpAddress(context:Context):String{
        return try{
            val w=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            android.text.format.Formatter.formatIpAddress(w.connectionInfo.ipAddress)
        }catch(e:Exception){"127.0.0.1"}
    }
}