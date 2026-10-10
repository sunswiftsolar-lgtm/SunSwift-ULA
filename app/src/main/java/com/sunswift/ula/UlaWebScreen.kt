package com.sunswift.ula

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebResourceErrorCompat
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
private const val ASSET_URL = "$ASSET_ORIGIN/assets/ula-web.html"
private const val MAX_BRIDGE_BYTES = 24 * 1024 * 1024

/** Native authentication remains the gate to the pinned live-web interface. */
@Composable
fun UlaWebScreen(state: UlaAppState, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { UlaApi() }
    val currentState by rememberUpdatedState(state)
    val currentLogout by rememberUpdatedState(onLogout)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    var geoRequest by remember { mutableStateOf<Pair<String, GeolocationPermissions.Callback>?>(null) }
    var pendingDownload by remember { mutableStateOf<NativeDownload?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        fileCallback?.onReceiveValue(if (success) captureUri?.let { arrayOf(it) } else null)
        fileCallback = null
        captureUri = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        geoRequest?.let { (origin, callback) ->
            callback.invoke(origin, granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true, false)
        }
        geoRequest = null
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val download = pendingDownload
        pendingDownload = null
        if (download != null) scope.launch {
            try {
                val uri = result.data?.data
                if (result.resultCode != Activity.RESULT_OK || uri == null) throw UlaApiException("File save cancelled.")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(download.bytes) }
                        ?: throw UlaApiException("The selected file cannot be written.")
                }
                download.respond(true, JSONObject().put("saved", true))
            } catch (failure: Exception) {
                download.respond(false, failure.message ?: "The file could not be saved.")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            geoRequest?.let { it.second.invoke(it.first, false, false) }
            WebStorage.getInstance().deleteAllData()
            webView?.apply { stopLoading(); removeAllViews(); destroy() }
        }
    }
    BackHandler {
        webView?.evaluateJavascript("(() => {const dialogs=[...document.querySelectorAll('dialog[open]')];if(dialogs.length)dialogs.at(-1).close();else window.scrollTo({top:0,behavior:'smooth'});})()", null)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF17324D)).safeDrawingPadding()) {
        error?.let { Text(it, modifier = Modifier.padding(16.dp)) }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = {
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    val assets = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                    webViewClient = object : WebViewClientCompat() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            assets.shouldInterceptRequest(request.url)

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (uri.toString() == ASSET_URL) return false
                            if (request.isForMainFrame && uri.scheme == "https") {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                            return true
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceErrorCompat) {
                            if (request.isForMainFrame) error = "The ULA interface could not load. Close and reopen the app."
                        }
                        // Certificate errors keep WebView's default cancellation behavior.
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            if (params.acceptTypes.any { it.startsWith("image/") }) {
                                AlertDialog.Builder(context).setTitle("Installation image")
                                    .setItems(arrayOf("Take photo", "Choose existing image")) { _, index ->
                                        if (index == 0) {
                                            val folder = File(context.cacheDir, "capture").apply { mkdirs() }
                                            val image = File.createTempFile("ula-", ".jpg", folder)
                                            captureUri = FileProvider.getUriForFile(context, "${context.packageName}.files", image)
                                            try { camera.launch(captureUri!!) } catch (_: Exception) {
                                                fileCallback?.onReceiveValue(null); fileCallback = null
                                                error = "No camera application is available. Choose an existing image."
                                            }
                                        } else picker.launch("image/*")
                                    }
                                    .setOnCancelListener { fileCallback?.onReceiveValue(null); fileCallback = null }.show()
                            } else picker.launch(params.acceptTypes.firstOrNull()?.ifBlank { "*/*" } ?: "*/*")
                            return true
                        }

                        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                            if (origin.trimEnd('/') != ASSET_ORIGIN) { callback.invoke(origin, false, false); return }
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                callback.invoke(origin, true, false)
                            } else {
                                geoRequest = origin to callback
                                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                        }

                        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                            AlertDialog.Builder(context).setMessage(message).setPositiveButton("OK") { _, _ -> result.confirm() }
                                .setOnCancelListener { result.cancel() }.show(); return true
                        }
                        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                            AlertDialog.Builder(context).setMessage(message).setPositiveButton("Confirm") { _, _ -> result.confirm() }
                                .setNegativeButton("Cancel") { _, _ -> result.cancel() }.setOnCancelListener { result.cancel() }.show(); return true
                        }
                        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                            val input = android.widget.EditText(context).apply { setText(defaultValue); isSingleLine = true }
                            AlertDialog.Builder(context).setMessage(message).setView(input)
                                .setPositiveButton("Confirm") { _, _ -> result.confirm(input.text.toString()) }
                                .setNegativeButton("Cancel") { _, _ -> result.cancel() }.setOnCancelListener { result.cancel() }.show(); return true
                        }
                    }

                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        error = "Update Android System WebView from Google Play to open ULA securely."
                        return@apply
                    }
                    WebViewCompat.addWebMessageListener(this, "ulaHost", setOf(ASSET_ORIGIN)) { _, message, origin, isMainFrame, reply ->
                        if (!isMainFrame || origin.toString().trimEnd('/') != ASSET_ORIGIN) return@addWebMessageListener
                        val raw = message.data ?: return@addWebMessageListener
                        if (raw.length > MAX_BRIDGE_BYTES * 2) return@addWebMessageListener
                        scope.launch {
                            var id = ""
                            fun respond(ok: Boolean, value: Any) {
                                reply.postMessage(JSONObject().put("id", id).put("ok", ok).put(if (ok) "result" else "error", value).toString())
                            }
                            try {
                                val request = JSONObject(raw)
                                id = request.getString("id")
                                val payload = request.optJSONObject("payload") ?: JSONObject()
                                val current = currentState
                                if (current.user == null || current.sessionToken.isBlank() || current.sessionExpiresAt <= System.currentTimeMillis()) {
                                    throw UlaApiException("Your session expired. Sign in with OTP again.")
                                }
                                when (request.getString("kind")) {
                                    "session" -> respond(true, current.sessionJson())
                                    "api" -> respond(true, api.webCall(current.sessionToken, payload.getString("method"), payload.optJSONArray("args") ?: JSONArray()))
                                    "scan" -> {
                                        if (!current.user.canEdit && !JSONObject(current.user.permissionsJson).optBoolean("installationEdit", false)) throw UlaApiException("Installation access is required.")
                                        respond(true, scanSerial(payload.getString("dataUrl")))
                                    }
                                    "download" -> {
                                        if (pendingDownload != null) throw UlaApiException("Finish saving the previous file first.")
                                        val encoded = payload.getString("dataUrl")
                                        val header = encoded.substringBefore(',')
                                        if (!header.startsWith("data:") || !header.endsWith(";base64")) throw UlaApiException("Unsupported file format.")
                                        val mime = header.removePrefix("data:").removeSuffix(";base64")
                                        if (mime !in setOf("application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "text/csv", "text/csv;charset=utf-8", "application/json", "image/jpeg", "image/png")) throw UlaApiException("Unsupported file type.")
                                        val bytes = Base64.decode(encoded.substringAfter(','), Base64.DEFAULT)
                                        if (bytes.size > MAX_BRIDGE_BYTES) throw UlaApiException("This file is too large to save.")
                                        val name = payload.optString("name", "ULA-report").substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\x00-\\x1f]"), "_").take(180)
                                        pendingDownload = NativeDownload(bytes) { ok, value -> respond(ok, value) }
                                        saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime.substringBefore(';'); putExtra(Intent.EXTRA_TITLE, name) })
                                    }
                                    "logout" -> { respond(true, JSONObject()); WebStorage.getInstance().deleteAllData(); currentLogout() }
                                    else -> throw UlaApiException("Unknown Android operation.")
                                }
                            } catch (failure: Exception) {
                                respond(false, failure.message ?: "ULA request failed.")
                            }
                        }
                    }
                    loadUrl(ASSET_URL)
                }
            }
        )
        Surface(color = Color.White) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrivacyPolicyLink()
            }
        }
    }
}

private data class NativeDownload(val bytes: ByteArray, val respond: (Boolean, Any) -> Unit)

private fun UlaAppState.sessionJson(): JSONObject = JSONObject()
    .put("sessionToken", sessionToken).put("expiresAt", sessionExpiresAt)
    .put("user", JSONObject().put("mobile", user!!.mobile).put("name", user.name).put("role", user.role)
        .put("permissions", JSONObject(user.permissionsJson)))

private suspend fun scanSerial(dataUrl: String): JSONObject = withContext(Dispatchers.Default) {
    if (!dataUrl.startsWith("data:image/jpeg;base64,") || dataUrl.length > 6 * 1024 * 1024) throw UlaApiException("Invalid scan image.")
    val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw UlaApiException("This image cannot be scanned.")
    val image = InputImage.fromBitmap(bitmap, 0)
    val scanner = BarcodeScanning.getClient()
    val textReader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val codes = scanner.process(image).awaitTask().mapNotNull { it.rawValue?.trim()?.uppercase() }
        val words = if (codes.isEmpty()) textReader.process(image).awaitTask().text.lines().flatMap { it.split(Regex("\\s+")) } else emptyList()
        val candidates = (codes + words).filter { it.matches(Regex("[A-Z0-9][A-Z0-9._/-]{4,79}")) && it.any(Char::isDigit) }.distinct()
        JSONObject().put("candidates", JSONArray(candidates.take(8))).put("source", if (codes.isEmpty()) "text" else "barcode")
    } catch (failure: MlKitException) {
        if (failure.errorCode != MlKitException.UNAVAILABLE) throw failure
        // Never infer a serial while the on-device model is still downloading.
        // Serial capture is optional; any detected number still needs confirmation.
        JSONObject().put("candidates", JSONArray()).put("source", "model-unavailable")
    } finally { scanner.close(); textReader.close(); bitmap.recycle() }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
}
