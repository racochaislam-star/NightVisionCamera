# 🌙 Night Vision Camera — دليل التثبيت الكامل

## هيكل المشروع

```
NightVisionCamera/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nightvision/camera/
│       │   ├── MainActivity.kt               ← الشاشة الرئيسية
│       │   ├── LocalRecordingActivity.kt     ← وضع التسجيل المحلي
│       │   ├── StreamingActivity.kt          ← وضع البث المباشر
│       │   ├── ViewerActivity.kt             ← مشاهدة البث
│       │   ├── NightVisionSettingsActivity.kt← إعدادات الليل
│       │   ├── camera/
│       │   │   └── NightVisionManager.kt     ← التحكم بالكاميرا
│       │   ├── service/
│       │   │   ├── RecordingService.kt       ← خدمة التسجيل
│       │   │   └── StreamingService.kt       ← خدمة البث
│       │   └── streaming/
│       │       └── MjpegServer.kt            ← خادم HTTP/MJPEG
│       └── res/
│           ├── layout/   (5 ملفات XML)
│           ├── values/   (colors, themes, strings)
│           └── drawable/ (icons, buttons)
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## خطوات التثبيت على Android Studio

### 1. تحميل Android Studio
- حمّل [Android Studio](https://developer.android.com/studio) آخر إصدار
- ثبّته واختر **Standard Installation**

### 2. فتح المشروع
```
File → Open → اختر مجلد NightVisionCamera
```
انتظر حتى ينتهي Gradle sync تلقائياً

### 3. إعداد الأذونات في Realme

افتح **الإعدادات** على الهاتف وطبّق التالي:

| الإعداد | المكان | القيمة |
|---------|--------|--------|
| تحسين البطارية | الإعدادات → البطارية → تحسين البطارية → ابحث عن التطبيق | **غير مُحسَّن** |
| التشغيل التلقائي | الإعدادات → إدارة التطبيقات → Night Vision Cam | **مفعّل** |
| الأذونات | الإعدادات → إدارة التطبيقات → الأذونات | كاميرا + تخزين + إشعارات = **مسموح** |
| عمل الخلفية | إعدادات متقدمة → قفل الشاشة | ابحث عن التطبيق وأضفه لـ **تجميد التطبيقات** = لا |

### 4. بناء وتشغيل
```
Build → Generate Signed Bundle/APK → APK
```
أو اضغط **▶ Run** مباشرة مع تفعيل USB Debugging

---

## كيفية الاستخدام

### وضع التسجيل المحلي 📹
1. افتح التطبيق → **التسجيل المحلي**
2. اختر الجودة: **720p** أو **1080p**
3. شغّل **الرؤية الليلية** (مفعّلة افتراضياً)
4. اضغط **▶ بدء التسجيل**
5. يمكنك إطفاء الشاشة — التسجيل يستمر في الخلفية
6. اضغط **⏹ إيقاف التسجيل** عند الانتهاء
7. الفيديو يُحفظ في: `هاتفك/Android/data/com.nightvision.camera/files/NightVision/`

### وضع البث المباشر 📡
**على هاتف الكاميرا (Realme):**
1. افتح التطبيق → **البث المباشر**
2. اضغط **▶ بدء البث**
3. ستظهر الرسالة: `http://192.168.x.x:8080/stream`
4. شارك هذا الرابط مع هاتف العرض

**على هاتف العرض (أي هاتف):**

**الطريقة 1 — داخل التطبيق:**
1. افتح التطبيق → **مشاهدة البث**
2. أدخل IP الكاميرا (مثال: `192.168.1.5`)
3. اضغط **اتصال**

**الطريقة 2 — عبر المتصفح:**
1. افتح Chrome/Safari
2. اكتب: `http://192.168.x.x:8080`
3. اضغط **Stream** في الصفحة التي تفتح

> ⚠️ **مهم:** يجب أن يكون كلا الهاتفين على نفس شبكة WiFi

---

## كيف تعمل الرؤية الليلية برمجياً

### المبدأ التقني
```
الكاميرا العادية (ISO 100, Exposure 8ms)
         ↓ في الظلام: تصبح الصورة مظلمة
         
الرؤية الليلية (ISO 3200, Exposure 66ms)
         ↓ 32× أكثر حساسية للضوء
         ✅ صورة مضاءة في الظلام
```

### ما يفعله الكود تحديداً

```kotlin
// 1. رفع ISO = أكثر حساسية للضوء
CaptureRequest.SENSOR_SENSITIVITY → 3200

// 2. إطالة وقت التعريض = ضوء أكثر يدخل
CaptureRequest.SENSOR_EXPOSURE_TIME → 66_000_000 (66ms)

// 3. تقليل الضوضاء الرقمية
CaptureRequest.NOISE_REDUCTION_MODE → HIGH_QUALITY

// 4. تحسين حواف الصورة
CaptureRequest.EDGE_MODE → HIGH_QUALITY

// 5. تعطيل AE التلقائي لنتحكم يدوياً
CaptureRequest.CONTROL_AE_MODE → AE_MODE_OFF
```

### الوضع التلقائي
```kotlin
// كل إطار: يقيس متوسط سطوع Y plane
avgBrightness = yuvBytes.average()
if (avgBrightness < threshold) enableNightVision()
```

---

## المخرجات والملفات

| الملف | المسار |
|-------|--------|
| فيديو مسجّل | `/Android/data/.../NightVision/*.mp4` |
| لقطات شاشة | `/Android/data/.../NightVision/Snapshots/*.jpg` |
| الإعدادات | SharedPreferences (محفوظة تلقائياً) |

---

## استكشاف الأخطاء

| المشكلة | الحل |
|---------|------|
| يتوقف عند قفل الشاشة | إعدادات → البطارية → غير مُحسَّن |
| لا يتصل البث | تأكد نفس الـ WiFi + لا firewall |
| الصورة مظلمة | ارفع ISO إلى 6400 في الإعدادات |
| خطأ في الأذونات | امنح جميع الأذونات يدوياً من الإعدادات |
| البث بطيء | استخدم 720p بدلاً من 1080p |

---

## المتطلبات

- Android 10+ (API 29)
- Realme UI 2.0 / 3.0 / 4.0 / 5.0
- Android Studio Hedgehog أو أحدث
- JDK 17
