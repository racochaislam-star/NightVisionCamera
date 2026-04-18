package com.nightvision.camera

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nightvision.camera.camera.NightVisionManager
import com.nightvision.camera.camera.NightVisionPrefs
import com.nightvision.camera.databinding.ActivityNightSettingsBinding

class NightVisionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNightSettingsBinding
    private lateinit var nightVisionManager: NightVisionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNightSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        nightVisionManager = NightVisionManager(this)
        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnReset.setOnClickListener { resetToDefaults() }

        // Auto/Manual mode toggle
        binding.switchAutoMode.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutManualControls.visibility =
                if (isChecked) android.view.View.GONE else android.view.View.VISIBLE
            binding.tvAutoModeDesc.text =
                if (isChecked) "يكتشف الإضاءة تلقائياً ويضبط الإعدادات"
                else "تحكم يدوي بالـ ISO ووقت التعريض"
        }

        // ISO seekbar
        binding.sbIso.max = 3
        binding.sbIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val iso = when (progress) {
                    0 -> NightVisionPrefs.ISO_LOW
                    1 -> NightVisionPrefs.ISO_MEDIUM
                    2 -> NightVisionPrefs.ISO_HIGH
                    3 -> NightVisionPrefs.ISO_MAX
                    else -> NightVisionPrefs.ISO_HIGH
                }
                binding.tvIsoValue.text = "ISO $iso"
                updateNightPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Exposure seekbar
        binding.sbExposure.max = 2
        binding.sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val desc = when (progress) {
                    0 -> "33ms (سريع)"
                    1 -> "66ms (متوسط)"
                    2 -> "100ms (بطيء - أكثر ضوءاً)"
                    else -> "66ms"
                }
                binding.tvExposureValue.text = "التعريض: $desc"
                updateNightPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Light threshold seekbar
        binding.sbLightThreshold.max = 100
        binding.sbLightThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThresholdValue.text = "حساسية الضوء: $progress"
                val desc = when {
                    progress < 30 -> "منخفضة جداً - لظلام شبه كامل"
                    progress < 60 -> "متوسطة - مناسبة للغرف المعتمة"
                    progress < 80 -> "عالية - يفعّل الليل عند أي تعتيم"
                    else -> "عالية جداً - دائماً في وضع الليل"
                }
                binding.tvThresholdDesc.text = desc
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Flash level buttons
        binding.btnFlashOff.setOnClickListener { selectFlash(0) }
        binding.btnFlashWeak.setOnClickListener { selectFlash(1) }
        binding.btnFlashMedium.setOnClickListener { selectFlash(2) }
        binding.btnFlashStrong.setOnClickListener { selectFlash(3) }
    }

    private fun loadSettings() {
        binding.switchAutoMode.isChecked = nightVisionManager.isAutoMode

        binding.sbIso.progress = when (nightVisionManager.isoLevel) {
            NightVisionPrefs.ISO_LOW -> 0
            NightVisionPrefs.ISO_MEDIUM -> 1
            NightVisionPrefs.ISO_HIGH -> 2
            NightVisionPrefs.ISO_MAX -> 3
            else -> 2
        }
        binding.tvIsoValue.text = "ISO ${nightVisionManager.isoLevel}"

        binding.sbExposure.progress = when (nightVisionManager.exposureNs) {
            NightVisionPrefs.EXPOSURE_SHORT -> 0
            NightVisionPrefs.EXPOSURE_MEDIUM -> 1
            NightVisionPrefs.EXPOSURE_LONG -> 2
            else -> 1
        }

        binding.sbLightThreshold.progress = nightVisionManager.lightThreshold
        binding.tvThresholdValue.text = "حساسية الضوء: ${nightVisionManager.lightThreshold}"

        selectFlash(nightVisionManager.flashLevel)

        binding.layoutManualControls.visibility =
            if (nightVisionManager.isAutoMode) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun selectFlash(level: Int) {
        nightVisionManager.flashLevel = level
        val selectedColor = getColor(R.color.accent_green)
        val defaultColor = getColor(R.color.bg_card)

        binding.btnFlashOff.setBackgroundColor(if (level == 0) selectedColor else defaultColor)
        binding.btnFlashWeak.setBackgroundColor(if (level == 1) selectedColor else defaultColor)
        binding.btnFlashMedium.setBackgroundColor(if (level == 2) selectedColor else defaultColor)
        binding.btnFlashStrong.setBackgroundColor(if (level == 3) selectedColor else defaultColor)

        val desc = when (level) {
            0 -> "🔦 الفلاش: مطفأ"
            1 -> "🔦 الفلاش: ضعيف"
            2 -> "🔦 الفلاش: متوسط"
            3 -> "🔦 الفلاش: قوي"
            else -> ""
        }
        binding.tvFlashDesc.text = desc
    }

    private fun saveSettings() {
        nightVisionManager.isAutoMode = binding.switchAutoMode.isChecked

        nightVisionManager.isoLevel = when (binding.sbIso.progress) {
            0 -> NightVisionPrefs.ISO_LOW
            1 -> NightVisionPrefs.ISO_MEDIUM
            2 -> NightVisionPrefs.ISO_HIGH
            3 -> NightVisionPrefs.ISO_MAX
            else -> NightVisionPrefs.ISO_HIGH
        }

        nightVisionManager.exposureNs = when (binding.sbExposure.progress) {
            0 -> NightVisionPrefs.EXPOSURE_SHORT
            1 -> NightVisionPrefs.EXPOSURE_MEDIUM
            2 -> NightVisionPrefs.EXPOSURE_LONG
            else -> NightVisionPrefs.EXPOSURE_MEDIUM
        }

        nightVisionManager.lightThreshold = binding.sbLightThreshold.progress

        Toast.makeText(this, "✅ تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetToDefaults() {
        nightVisionManager.isAutoMode = true
        nightVisionManager.isoLevel = NightVisionPrefs.ISO_HIGH
        nightVisionManager.exposureNs = NightVisionPrefs.EXPOSURE_MEDIUM
        nightVisionManager.lightThreshold = 60
        nightVisionManager.flashLevel = NightVisionPrefs.FLASH_OFF
        loadSettings()
        Toast.makeText(this, "تمت إعادة الضبط إلى الافتراضي", Toast.LENGTH_SHORT).show()
    }

    private fun updateNightPreview() {
        val iso = when (binding.sbIso.progress) {
            0 -> "منخفض (800)"
            1 -> "متوسط (1600)"
            2 -> "عالٍ (3200) ★"
            3 -> "أقصى (6400)"
            else -> ""
        }
        val exposure = when (binding.sbExposure.progress) {
            0 -> "قصير (أقل ضوء)"
            1 -> "متوسط ★"
            2 -> "طويل (أكثر ضوء)"
            else -> ""
        }
        binding.tvPreviewDesc.text = "الحساسية: $iso | التعريض: $exposure"
    }
}
