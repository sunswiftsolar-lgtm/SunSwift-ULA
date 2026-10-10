# SunSwift ULA Android

Android 1.6.0 (build 8), retaining the previous native authentication application and the deployed ULA web application Version 45.12. The release is pinned in `reference/web-release.json`; historical release artifacts and metadata are retained.

## Application behavior

- Uses Firebase mobile OTP login with a five-minute OTP expiry.
- Provides the public [Privacy Policy](https://ula-dda9e-privacy.web.app/) before login, on the OTP screen and in a native footer after login. The policy opens in an external browser without the ULA session token or authenticated bridge.
- Displays Firebase phone-verification and abuse-prevention disclosure with unchecked consent required before requesting an OTP. Changing the mobile number resets consent; authorized-user and OTP-expiry checks remain unchanged.
- Checks the live ULA authorized-user list before requesting an OTP; unlisted or inactive users are denied access.
- Re-validates authorization on the server when creating an app session.
- Supports the current Admin, Editor and Installer roles, using the server's permissions.
- Uses the same live Google Sheet database and Apps Script deployment as the web application.
- Bundles the Version 45.12 responsive web interface inside an origin-restricted Android WebView. Native Firebase authentication remains the login gate; this is a hybrid application, not an independently reimplemented form.
- Preserves every web form control and the latest hierarchy, dashboards, filters, reports, incentives and administration screens.
- Includes Division, Sub Division, Section, Panchayat and Village hierarchy, transportation distances and latest stage/amount analytics.
- Provides admin-only User control and User activity screens.
- Shows precise before-and-after values for modified fields in the activity history.
- Uses the exact capabilities and expiry returned by the live server. The current backend ends weekly sessions on Saturday at 23:59:59 IST; the app does not impose an outdated daily deadline or infer permissions from a role name.
- Saves generated PDF and Excel files with Android's file picker.
- Uses the measured Android viewport height to avoid collapsed report and administration dialogs on embedded WebViews.
- Supports system-camera/image selection, GPS capture, and on-device barcode/OCR recognition. Serial numbers are optional for capture, matching the latest web app. A detected number must still be reviewed and confirmed; cancellation or an invalid confirmed serial blocks that upload.
- Barcode/OCR models are delivered by Google Play Services, requested at installation. If a model is unavailable or no valid serial is detected, capture can continue without assigning a serial. Recognition itself remains on-device.
- Includes Survey done by, Installer4 and the latest first-save installer locking rules, daily/weekly/monthly activity, status permissions, and the two-page authorization-letter template.
- Does not allow arbitrary remote pages to call the native bridge. HTTPS-only networking, no certificate bypass, no public JavaScript interface, and no session backup.

## Backend compatibility

The existing Apps Script URL is unchanged. UI Version 45.12 was deployed as immutable Apps Script version 58 on 10 October 2026, restoring the Android compatibility adapter that was absent in version 57. The patch adds only three `doPost` cases and the explicit adapter; it preserves the existing configuration, database, permissions and UI. Version 57 remains available for rollback. No records or user privileges were changed.

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
node scripts/verify-privacy.mjs
```

`scripts/prepare-web.mjs` rebuilds `ula-web.html` from a locally downloaded Apps Script HTML response at `reference/web-<pinned-version>-response.html`. Original source hashes and every form-control name are recorded in `reference/manifest.json`. The adapter changes authentication, native API transport, file saving and serial scanning only; all 33 latest web controls remain unchanged. Changing the release pin requires downloading and verifying the matching live source before regeneration.

`node scripts/preview-interface.mjs` starts an optional loopback-only preview at `http://127.0.0.1:8876/?role=ADMIN`. EDITOR and INSTALLER fixtures are also available. It uses synthetic data, disables record/user mutations and writes test downloads only under ignored `test-results/interface`. It never connects to the live ULA server. This is not a production login mechanism and is not bundled in the APK.

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
- The original code-6 release preview reported no errors and warnings for no testers and no deobfuscation file. Release minification is disabled, so this build has no R8 mapping file. The five owner-provided tester accounts were subsequently saved/selected; no invitation or rollout was sent. The outstanding user-assisted device checks remain pending.

## Important distribution note

The debug APK (`com.sunswift.ula.debug`) is suitable only for controlled testing and uses the existing Firebase registration. The new **ULA Data Management** Play application uses `com.sunswift.ula`. Its upload key was created locally with explicit user approval. Do not generate another identity or replace it automatically.

On the original Windows account, `./scripts/build-play-release.ps1` builds the signed release using `.signing/ula-upload.p12` and a Windows-DPAPI-encrypted password. Both are excluded from Git. Keep a secure recoverable backup of the key and its password outside GitHub; DPAPI alone is tied to this Windows account. Never commit signing credentials or place them in CI logs.

The last confirmed internal-testing draft before this update was Android `1.5.1-web-v45.7`, version code `7`. Build 8 is Android `1.6.0-web-v45.12`; check its release metadata for the observed upload/rollout status. Public release/privacy declarations and device tests are not certified complete merely because Play accepts an upload.

Distribution is **internal testing only**. The user authorized the internal-testing preparation and provided five tester accounts, now saved/selected. Activate only the internal track after applicable release checks are ready. Do not publish to production or enable open testing without a separate explicit request.

## Privacy update on 4 October 2026

- Only the approved public policy and robots.txt were published to the dedicated Firebase Hosting site `ula-dda9e-privacy`. No office records, internal plans or credentials were uploaded.
- Play Console saved `https://ula-dda9e-privacy.web.app/` in the Privacy policy field and displayed “Change saved. Send for review in Publishing overview.” No review submission or rollout was performed.
- Android 1.5.1, version code 7, adds public policy links and phone-authentication consent. Source regressions, signed release build and release lint pass (0 errors, 15 warnings). APK ZIP and APK/AAB ARM64/x86-64 LOAD/RELRO alignment pass. The upload certificate is unchanged.
- The signed APK was installed and launched on the connected LAVA phone without removing its debug app or data. The initial login showed the policy link, disclosure, unchecked consent and disabled Send OTP. The user-operated OTP screen showed the policy link and expiry countdown. The owner explicitly confirmed that the policy link opens after login; this is user-reported, not independently captured in the external browser. No SMS was requested by the agent and no office records were modified.
- An intermediate UI dump showed the native footer and a WebView, but the later screenshot was back at login. The subsequent owner confirmation is the evidence for successful logged-in policy opening; footer presence alone must not be counted as completed login.
- GitHub [passed build, lint, privacy/bridge regressions and native alignment](https://github.com/sunswiftsolar-lgtm/SunSwift-ULA/actions/runs/37178017316) for code commit `00cbb086adc3d81e82945daa9734989155d388b1`.
- Play accepted build 7 and saved the existing internal-testing draft with updated release notes. Code 6 was removed only from this draft and remains recoverable in Play's artifact library and the local immutable copies. No rollout or review submission was made. Exact hashes and remaining checks are recorded in `reference/play-release-7.json`.
- The existing daily-backup Head setting was changed from 30 to the separately approved 90 days, saved and verified after reload. The existing trigger was not changed or run manually. Actual future execution, manual log retention and verified deletion-request handling remain unverified.
- Remaining Play declarations and device checks are not certified complete merely because the policy is public or its URL is saved. The original code-6 immutable artifacts and release metadata are preserved.

## Version 45.12 update on 10 October 2026

- Downloaded the fresh public deployment and verified Version 45.12, not the stale 45.10 open-tab content. Source and adapted hashes are recorded in the manifest.
- All 33 latest form controls are preserved. Automated checks pass for all protected web delegates, prototype rejection, malformed arguments, bridge success/error/timeout, viewport handling, optional serial capture, detected-number review and cancellation, privacy consent, exact server capabilities and server-controlled session expiry.
- Signed release build and lint passed: 0 errors, 9 warnings. APK and AAB ARM64/x86-64 LOAD and RELRO checks pass; APK ZIP alignment passes. The upload certificate, package name, SDK target and permission set are unchanged.
- Live Android read-only checks reject invalid sessions for records/admin calls and unknown prototype operations. The restored preflight rejects an invalid mobile without requesting SMS.
- A synthetic fixture generated a two-page authorization letter and an Excel export through the actual bundled UI and native-download adapter. PDF pages were rendered and checked for the correct synthetic consumer, address and consumer number. These tests do not certify Android file-picker behavior or legal validity of the template.
- Actual 412-pixel viewport measurements confirm a single-column layout without page overflow. Synthetic record links load Survey done by and Installer4 into the matching edit form. Physical-phone, camera/GPS, role/scope and 16 KB runtime checks require their own observed evidence.
- Consumer count was 268 before the backend update. No consumer or user records were changed by the update or synthetic tests.
- Play accepted the signed code-8 AAB and saved the internal-testing draft with updated release name and notes. Code 7 was removed only from this draft; Play explicitly retains it in the artifact library, and local immutable copies remain unchanged.
- Internal preview shows no errors and one deobfuscation-file warning. Release minification is disabled, so no R8 mapping file is generated. The release was initially saved as a draft without activation.
- After the owner explicitly approved starting internal testing for the five selected testers, Play activated code 8. The track shows Active and Available to internal testers. The existing ULA Office Internal Testers list remained selected with 5 users; membership was not changed. The tester join-link control is enabled. No production/open-testing action was taken.
- Play labels this internal app Not reviewed and temporarily names it `com.sunswift.ula (unreviewed)` until setup/review is complete. Activation does not certify public-release declarations or the still-pending physical-phone, camera/GPS and 16 KB runtime tests.
