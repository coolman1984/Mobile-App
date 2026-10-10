# Pixel Plus Owner | تطبيق قيادة الشركة 📱

Native Android app for the company owner. It speaks Arabic, right to left.

Two modes:

- **Demo**, when no hub is saved. Companies, requests and alerts are fictional and stay in memory. The screen says so.
- **Live hub**, after the owner saves an `https` address and the owner token. Pending licence requests and the licence event log come from the Cloudflare Worker. Approving a request only records the decision. Rejecting it closes the request. The phone never signs a code.

No signing key, no Firebase, and no cleartext traffic. Tokens stay in the Android Keystore (`EncryptedSharedPreferences`). The telemetry pull token is optional. The app never calls `POST /ack`.

## Owner steps | خطوات المالك

1. Install the debug APK on the phone.  
   ثبّت ملف التطبيق على الهاتف.
2. Open **الإعدادات**.  
   افتح «الإعدادات».
3. Paste the hub URL, starting with `https`, and the owner token (`LICENCE_ADMIN_TOKEN`). Paste the token alone, without the word Bearer. The telemetry token (`RELAY_PULL_TOKEN`) is optional.  
   الصق عنوان المركز الذي يبدأ بـ `https`، ثم رمز المالك. الصق الرمز وحده من غير كلمة Bearer. رمز سحب القياسات اختياري.
4. Tap **اختبار الاتصال**, then **حفظ**. Allow local notifications if the phone asks.  
   اضغط «اختبار الاتصال» ثم «حفظ». اسمح بالإشعارات المحلية إذا طلب الهاتف ذلك.

Example hub: `https://factory-hub.mflma2040.workers.dev`

Approval from the phone works only after the Worker that understands `POST /licence/decide` with `{"action":"approve"}` is deployed. That action records the decision and does not accept a code. Refusal (`action: refuse`) already works on the current Worker. Issuing the code stays in Licence Studio on the owner's PC. Paid requests still need the payment confirmation there.

The phone checks for new pending requests and new licence events about every 15 minutes when a network is available, and posts a local notification only for items it has not seen before. The first check does not notify.

**مسح الإعدادات** deletes the saved address and tokens and returns the app to the labelled demo.

## What this build does not do

- It does not generate or store a licence code or a private key.
- It does not call `POST /ack`, so telemetry batches stay on the Worker until the Control Center pulls them.
- It does not use Firebase or a paid push service.
- A green CI build is not a test on a physical phone.

## Build

Open `apps/pixel-plus-owner-mobile` in Android Studio (Android SDK 35, JDK 17). Or, with Gradle 8.11.1:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

GitHub Actions (`.github/workflows/pixel-plus-owner-android.yml`) runs those tasks and uploads the debug APK artifact `pixel-plus-owner-debug-apk`. This is not a Play Store release.

Build coordinates are locked: AGP 8.9.2, Gradle 8.11.1, JDK 17, Android SDK 35, build-tools 35.0.0, Kotlin 2.1.20, Jetpack Compose BOM 2025.04.01. Library versions are fixed in the Gradle files. No floating versions. The build checks that none of those libraries need an Android SDK newer than 35, and that the debug APK has no release signature and no secret. KSP is pinned but not applied yet.

A Cairo clock lives in `domain/Clock.kt`. It follows Africa/Cairo, including the skipped hour when summer time starts and the repeated hour when it ends. It is not a new screen. The demo and the live hub behave as before.

ساعة القاهرة تعرف التوقيت الصيفي، ولا تضيف شاشة. الوضع التجريبي والاتصال بالمركز كما هما. توقيع نسخة النشر ما زال متوقفًا.

## For agents

Read `AGENTS.md`, `ROADMAP.md` and `SECURITY_AND_COMPATIBILITY.md` here, then the Worker contract in `templates/telemetry-relay/src/licence.js`. Do not put a signing key in this app. Keep `realIssuanceEnabled` false.

Source of truth for later phases: [ROADMAP.md](ROADMAP.md). Security: [SECURITY_AND_COMPATIBILITY.md](SECURITY_AND_COMPATIBILITY.md).
