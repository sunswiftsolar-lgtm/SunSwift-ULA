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
- Uses the measured Android viewport height to avoid collapsed report and administration dialogs on embedded WebViews.
- Supports system-camera/image selection, GPS capture, and on-device barcode/OCR recognition. Serial numbers must be checked and confirmed against the picture before uploading.
- Barcode/OCR models are delivered by Google Play Services, requested at installation. If a model is still downloading, the user can manually confirm a valid serial; cancellation and invalid serials still block upload. Recognition itself remains on-device.
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

Open this folder in Android Studio with JDK 17, Android SDK Platform 36, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1. The project uses Gradle 8.11.1 / Android Gradle Plugin 8.10.1 / Kotlin and Compose compiler 2.1.20 and targets Android 16 (API 36). Run:

```powershell
./gradlew.bat assembleDebug
```

For the Windows Java socket issue encountered on this workstation, PowerShell 7 can run `./scripts/build-local.ps1`. It applies compatibility options only to the build and its children, without changing global Java settings.

The official AndroidX graphics-path 1.1.0 native sources are retained under `vendor/androidx-graphics-path` with their license. A separate app CMake wrapper rebuilds the identical JNI implementation with both 16 KB page-size linker flags. CI checks the actual packaged replacement, including RELRO; it must not silently fall back to the incompatible upstream prebuilt. See the vendor README for the pinned source and archive hash.

Verify bundled UI parity and the bridge before building:

```text
node scripts/verify-web.mjs
```

`scripts/prepare-web.mjs` rebuilds the asset from a locally downloaded Apps Script HTML response saved as `reference/web-45.7-response.html`. Original source hashes are recorded in `reference/manifest.json`. The adapter changes authentication, native API transport, file saving and serial scanning only; all form controls remain unchanged.

The installable debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
The prior internal APK remains in the local `releases` folder. APKs, signing keys, build caches and device screenshots are excluded from Git.

## Verification on 3 October 2026

- Android API 36 debug build and lint completed successfully (0 errors).
- The private GitHub build check passed; reference-control parity, API allowlist, bridge responses/timeouts and viewport resizing have automated checks.
- The connected LAVA phone authenticated as Administrator, restored the live session after updates and loaded 268 records.
- Admin User activity displayed login/update history and precise before/after comments. The Reports panel now displays correctly with the live 268-record preview.
- A valid-format unlisted test number was rejected without requesting an SMS; invalid app sessions were rejected by the server.
- PDF/Excel saved-file checks, Editor/Installer phone tests, camera/GPS tests and a Studio run are not yet complete. No consumer records were modified during testing.
- The initial debug build passed ZIP/LOAD alignment but exposed five native RELRO issues. The signed release preparation below resolves those bundled-library issues with stricter checks.

### Signed release preparation

- Release build, signed AAB/APK and release lint completed successfully (0 errors, 14 warnings).
- The final APK passes 16 KB ZIP alignment. All bundled ARM64 and x86-64 native LOAD and RELRO segments pass strict checks in both the APK and AAB. A runtime test on a 16 KB device/emulator is still pending; the connected LAVA phone uses 4 KB pages.
- The production Firebase client is `com.sunswift.ula`, app ID `1:422470553947:android:12a7a37254935aa8428a7e`, in the same `ula-dda9e` project. The genuine downloaded configuration includes production and debug clients. Public SHA-1/SHA-256 fingerprints for the local upload key and current Play app-signing key are registered.
- The signed production APK was installed and launched without replacing the debug app. The user subsequently reported completion of the production OTP/dashboard step; that is user-reported evidence, not an independently observed production test. On the later preflight, the phone was locked and only the debug package was present, so the production retest awaits unlock and reinstall. The earlier admin checks above were on the debug app.
- Mandatory manual serial confirmation, model-unavailable fallback, cancellation and invalid input have automated regression checks.
- Play Console accepted the signed AAB and saved release `1.5.0-web-v45.7 (6)` as **Draft** on the **Inactive** internal-testing track. No testing rollout, production release or tester invitations were made. Exact artifact hashes and non-secret signing fingerprints are recorded in `reference/play-release-6.json`.
- The latest GitHub build [passed all checks](https://github.com/sunswiftsolar-lgtm/SunSwift-ULA/actions/runs/37118546530). Local bridge/control/serial regressions, strict AAB native alignment, APK ZIP alignment and immutable artifact hashes were rechecked successfully during the next preflight.
- Play's release preview reports no errors and two warnings: no testers selected and no deobfuscation file. Release minification is disabled, so this build has no R8 mapping file. Approved tester emails, privacy-policy details and the outstanding user-assisted device checks are still pending.

## Important distribution note

The debug APK (`com.sunswift.ula.debug`) is suitable only for controlled testing and uses the existing Firebase registration. The new **ULA Data Management** Play application uses `com.sunswift.ula`. Its upload key was created locally with explicit user approval. Do not generate another identity or replace it automatically.

On the original Windows account, `./scripts/build-play-release.ps1` builds the signed release using `.signing/ula-upload.p12` and a Windows-DPAPI-encrypted password. Both are excluded from Git. Keep a secure recoverable backup of the key and its password outside GitHub; DPAPI alone is tied to this Windows account. Never commit signing credentials or place them in CI logs.

The internal-testing draft bundle is Android `1.5.0-web-v45.7`, version code `6`. Public release/privacy declarations and the remaining device tests are not certified complete merely because Play accepts a draft upload.

Distribution is **internal testing only**. The user authorized proceeding with the preflight and internal test on 3 October 2026, but no testers have been approved or selected yet. Activate only the internal track after the tester list and applicable release checks are ready. Do not publish to production or enable open testing without a separate explicit request.
