# SunSwift ULA Android

Android 1.5.0, based on the previous native Android 1.4.0 application and the deployed ULA web application Version 45.7.

## Application behavior

- Uses Firebase mobile OTP login with a five-minute OTP expiry.
- Checks the live ULA authorized-user list before requesting an OTP; unlisted or inactive users are denied access.
- Re-validates authorization on the server when creating an app session.
- Supports the current Admin, Editor and Installer roles, using the server's permissions.
- Uses the same live Google Sheet database and Apps Script deployment as the web application.
- Bundles the exact Version 45.7 responsive web interface inside an origin-restricted Android WebView. Native Firebase authentication remains the login gate; this is a hybrid application, not an independently reimplemented form.
- Preserves every web form control and the latest hierarchy, dashboards, filters, reports, incentives and administration screens.
- Includes Division, Sub Division, Section, Panchayat and Village hierarchy, transportation distances and latest stage/amount analytics.
- Provides admin-only User control and User activity screens.
- Shows precise before-and-after values for modified fields in the activity history.
- Keeps the authenticated session until logout or 23:59:59 IST.
- Saves generated PDF and Excel files with Android's file picker.
- Supports system-camera/image selection, GPS capture, and on-device barcode/OCR recognition. Serial numbers must be checked and confirmed against the picture before uploading.
- Does not allow arbitrary remote pages to call the native bridge. HTTPS-only networking, no certificate bypass, no public JavaScript interface, and no session backup.

## Backend compatibility

The existing Apps Script URL is unchanged. UI Version 45.7 was deployed as Apps Script immutable version 54 on 3 October 2026 with the Android compatibility adapter. A fresh database backup completed before that change.

The adapter in `backend/AndroidApi.gs` delegates only 27 named methods. Existing server functions still validate each session and role. Add these cases to the existing `doPost` action switch when applying the adapter to another deployment:

```javascript
case 'checkLoginMobile': result = checkLoginMobile(payload.mobile); break;
case 'getPublicAuthConfig': result = getPublicAuthConfig(); break;
case 'webCall': result = mobileAndroidCall_(payload.sessionToken, payload.method, payload.args); break;
```

Do not replace the existing database, run initial setup against a new Sheet, or bypass `requireUser_`. The web source and consumer records are not included as database exports in this repository.

## Build

Open this folder in Android Studio with JDK 17, Android SDK Platform 36 and Build Tools 36.0.0. The project uses Gradle 8.11.1 / Android Gradle Plugin 8.10.1 and targets Android 16 (API 36). Run:

```powershell
./gradlew.bat assembleDebug
```

For the Windows Java socket issue encountered on this workstation, PowerShell 7 can run `./scripts/build-local.ps1`. It applies compatibility options only to the build and its children, without changing global Java settings.

Verify bundled UI parity and the bridge before building:

```text
node scripts/verify-web.mjs
```

`scripts/prepare-web.mjs` rebuilds the asset from a locally downloaded Apps Script HTML response saved as `reference/web-45.7-response.html`. Original source hashes are recorded in `reference/manifest.json`. The adapter changes authentication, native API transport, file saving and serial scanning only; all form controls remain unchanged.

The installable debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
The prior internal APK remains in the local `releases` folder. APKs, signing keys, build caches and device screenshots are excluded from Git.

## Important distribution note

The debug APK (`com.sunswift.ula.debug`) is suitable only for controlled testing and uses the existing Firebase registration. A Play Store update must use the existing Play package, organization-owned upload key and matching Firebase registration. Do not generate a replacement signing identity without confirming the existing app's configuration.

Upload only a signed release AAB to **ULA Data Management → Internal testing** and save it as a draft. Do not start a testing rollout or publish to production as part of this request.
