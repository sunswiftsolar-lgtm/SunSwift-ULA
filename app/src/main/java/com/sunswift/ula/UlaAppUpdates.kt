package com.sunswift.ula

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

internal const val ULA_INTERNAL_TEST_URL = "https://play.google.com/apps/internaltest/4701012090191547671"
internal val LocalUlaAppUpdates = staticCompositionLocalOf<UlaAppUpdates?> { null }
internal enum class UpdateStage { IDLE, CHECKING, CURRENT, AVAILABLE, STORE, DOWNLOADING, READY, ERROR, TEST }

/** Play determines eligibility/version/signature. Never downloads or installs arbitrary APKs. */
internal class UlaAppUpdates(
    context: Context,
    private val manager: AppUpdateManager,
    private val installedCode: Int = BuildConfig.VERSION_CODE,
    private val testBuild: Boolean = BuildConfig.DEBUG,
    private val launch: (AppUpdateInfo) -> Boolean
) {
    private val preferences = context.applicationContext.getSharedPreferences("ula_app_updates", 0)
    private val handler = Handler(Looper.getMainLooper())
    private var request = 0
    private var busy = false
    private var disposed = false
    var stage by mutableStateOf(UpdateStage.IDLE); private set
    var visible by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private val listener = InstallStateUpdatedListener { state ->
        if (!disposed) when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> showReady()
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> {
                stage = UpdateStage.DOWNLOADING
                message = "Google Play is downloading the update. You can keep using ULA."
            }
            InstallStatus.FAILED, InstallStatus.CANCELED -> fail("Update interrupted. You can keep using ULA and try again later.")
        }
    }
    init { if (!testBuild) manager.registerListener(listener) }

    fun check(manual: Boolean = false) {
        if (disposed) return
        if (manual) visible = true
        if (testBuild) {
            if (manual) {
                stage = UpdateStage.TEST
                message = "This is the isolated TEST app. Open SunSwift ULA (without TEST) for office work. Test builds do not receive the internal Play release."
            }
            return
        }
        if (busy) return
        busy = true
        val currentRequest = ++request
        if (manual) { stage = UpdateStage.CHECKING; message = "Checking Google Play…" }
        handler.postDelayed({
            if (!disposed && busy && request == currentRequest) {
                busy = false; request++
                if (manual) fail(CHECK_FAILED)
            }
        }, 15_000)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (disposed || request != currentRequest) return@addOnSuccessListener
            busy = false
            when {
                info.installStatus() == InstallStatus.DOWNLOADED -> showReady()
                info.installStatus() in setOf(InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING) -> {
                    stage = UpdateStage.DOWNLOADING
                    message = "Google Play is updating ULA. You can keep working until it is ready."
                }
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.availableVersionCode() > installedCode -> {
                    stage = if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) UpdateStage.AVAILABLE else UpdateStage.STORE
                    message = "A newer ULA build (${info.availableVersionCode()}) is available to your Google Play account. Download it now or continue working."
                    val now = System.currentTimeMillis()
                    if (manual || preferences.getInt("prompt_code", 0) != info.availableVersionCode() ||
                        now - preferences.getLong("prompt_at", 0) >= 86_400_000) {
                        visible = true
                        preferences.edit().putInt("prompt_code", info.availableVersionCode()).putLong("prompt_at", now).apply()
                    }
                }
                else -> {
                    stage = UpdateStage.CURRENT
                    message = "Google Play reports no newer update available to this account. Internal testers must use their selected Google account."
                }
            }
        }.addOnFailureListener {
            if (disposed || request != currentRequest) return@addOnFailureListener
            busy = false
            // A sideloaded/unlicensed app or no internet is not proof that it is current.
            if (manual) fail(CHECK_FAILED)
        }
    }

    fun download() {
        if (busy || disposed || testBuild) return
        busy = true
        stage = UpdateStage.CHECKING; message = "Opening Google Play’s update confirmation…"
        val currentRequest = ++request
        handler.postDelayed({
            if (!disposed && busy && request == currentRequest) { busy = false; request++; fail(CHECK_FAILED) }
        }, 15_000)
        // A Play AppUpdateInfo can be consumed only once; obtain a fresh one on every attempt.
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (disposed || currentRequest != request) return@addOnSuccessListener
            busy = false
            if (info.installStatus() == InstallStatus.DOWNLOADED) { showReady(); return@addOnSuccessListener }
            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE ||
                info.availableVersionCode() <= installedCode || !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                stage = UpdateStage.STORE; message = "Open internal testing to check the release available to your account."
                return@addOnSuccessListener
            }
            try {
                if (launch(info)) { visible = false } else fail("Google Play could not start the update. Try internal testing instead.")
            } catch (_: Exception) { fail("Google Play could not start the update. Try internal testing instead.") }
        }.addOnFailureListener {
            if (!disposed && request == currentRequest) { busy = false; fail(CHECK_FAILED) }
        }
    }

    fun onFlowResult(result: Int) {
        if (result == Activity.RESULT_OK) {
            stage = UpdateStage.DOWNLOADING; message = "Google Play is downloading the update. You can keep using ULA."
        } else {
            stage = UpdateStage.ERROR
            message = if (result == Activity.RESULT_CANCELED) "Update cancelled. Nothing was removed; you can keep using ULA." else "Update could not start. You can retry later."
            visible = true
        }
    }
    fun complete() {
        if (stage != UpdateStage.READY || busy || disposed) return
        // Explicit user action only: restart may discard unsaved form edits, not encrypted photos.
        busy = true
        stage = UpdateStage.DOWNLOADING; message = "Google Play is installing the downloaded update…"
        manager.completeUpdate().addOnFailureListener {
            if (!disposed) { busy = false; fail("Update could not finish. Please try again later.") }
        }
    }
    fun dismiss() { visible = false }
    fun dispose() {
        disposed = true; request++; handler.removeCallbacksAndMessages(null)
        if (!testBuild) manager.unregisterListener(listener)
    }
    private fun showReady() {
        stage = UpdateStage.READY; visible = true
        message = "Update downloaded. Save any unsaved form changes before restarting. Saved phone photos will not be deleted."
    }
    private fun fail(text: String) { stage = UpdateStage.ERROR; message = text; visible = true }
    private companion object {
        const val CHECK_FAILED = "Cannot check Google Play right now. Check internet and your internal-tester Google account. This locally installed APK may not be eligible for Play in-app updates; do not uninstall it if it contains saved photos."
    }
}

@Composable
internal fun AppUpdatesLink() {
    val updates = LocalUlaAppUpdates.current ?: return
    TextButton(onClick = { updates.check(manual = true) }) { Text("App updates") }
}

@Composable
internal fun AppUpdateDialog(updates: UlaAppUpdates) {
    if (!updates.visible) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = updates::dismiss,
        title = { Text(if (BuildConfig.DEBUG) "SunSwift ULA TEST" else "SunSwift ULA updates") },
        text = { Text("Installed: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n\n${updates.message}") },
        confirmButton = {
            when (updates.stage) {
                UpdateStage.AVAILABLE -> TextButton(onClick = updates::download) { Text("Download update") }
                UpdateStage.READY -> TextButton(onClick = updates::complete) { Text("Restart and update") }
                UpdateStage.STORE, UpdateStage.ERROR -> TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ULA_INTERNAL_TEST_URL)).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) { /* Dialog remains available to retry. */ }
                }) { Text("Open internal testing") }
                UpdateStage.CURRENT -> TextButton(onClick = { updates.check(manual = true) }) { Text("Check again") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = updates::dismiss) { Text("Close") } }
    )
}
