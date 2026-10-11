package com.sunswift.ula

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Uploads only with the original photographer's live, server-validated session. */
class PhotoUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val queue = PhotoQueue(applicationContext)
        val preferences = applicationContext.getSharedPreferences("ula_native_session", 0)
        val assignedNetwork = if (Build.VERSION.SDK_INT >= 28) network else null
        val api = UlaApi(assignedNetwork)
        Log.i(TAG, "Upload drain started; attempt=$runAttemptCount assignedNetwork=${assignedNetwork != null}")
        var retry = false
        for (item in queue.all().filter { it.optString("status") !in setOf("uploaded", "superseded") && it.optJSONObject("gps") != null }) {
            val id = item.getString("id")
            val mobile = item.getString("ownerMobile")
            val token = preferences.getString("session_token", "").orEmpty()
            val expiry = preferences.getLong("session_expires_at", 0)
            val firebaseMobile = FirebaseAuth.getInstance().currentUser?.phoneNumber
            if (token.isBlank() || expiry <= System.currentTimeMillis() || firebaseMobile != mobile) {
                queue.update(id) { it.put("status", "login").put("message", "Photo safe on phone · original user must sign in to upload") }
                continue
            }
            var stage = "session"
            try {
                val session = api.resumeSession(token)
                if (session.user.mobile != mobile || (!session.user.canEdit && !JSONObject(session.user.permissionsJson).optBoolean("installationEdit")))
                    throw UlaApiException("Installation access is required for the original user.")
                val consumer = item.getJSONObject("record").getString("consumerNo")
                val type = item.getString("imageType")
                // Read back before retrying an uncertain response, and never overwrite a newer capture.
                stage = "readback"
                val listed = api.webCall(token, "listConsumerImages", JSONArray().put(consumer)).optJSONArray("images") ?: JSONArray()
                val existing = (0 until listed.length()).map { listed.getJSONObject(it) }.firstOrNull { it.optString("imageType") == type }
                if (existing != null && existing.optString("capturedAt") >= item.getString("capturedAt")) {
                    queue.update(id) { it.put("status", if (existing.optString("capturedAt") == item.getString("capturedAt")) "uploaded" else "superseded")
                        .put("message", if (existing.optString("capturedAt") == item.getString("capturedAt")) "Uploaded · original also safe on phone" else "Newer image is on server · this original remains safe on phone").put("image", existing) }
                    continue
                }
                queue.update(id) { it.put("status", "uploading").put("message", "Saved on phone · processing/uploading in background") }
                var jpeg = queue.prepared(item)
                if (jpeg == null) {
                    stage = "prepare"
                    val gps = item.getJSONObject("gps")
                    val address = try {
                        api.webCall(token, "reverseGeocodeLocation", JSONArray().put(gps.getDouble("latitude")).put(gps.getDouble("longitude"))).optString("formattedAddress")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { "Coordinates recorded at capture; place name unavailable" }
                    jpeg = PhotoStamp.prepare(queue.raw(item), item, address.ifBlank { "Coordinates recorded at capture; place name unavailable" })
                    queue.savePrepared(item, jpeg)
                }
                // Recheck credentials after processing; do not upload as a user who logged out meanwhile.
                if (preferences.getString("session_token", "") != token || FirebaseAuth.getInstance().currentUser?.phoneNumber != mobile)
                    throw UlaApiException("Original user must sign in to upload.")
                val gps = item.getJSONObject("gps")
                val payload = JSONObject().put("consumerNo", consumer).put("imageType", type)
                    .put("dataUrl", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .put("latitude", gps.getDouble("latitude")).put("longitude", gps.getDouble("longitude"))
                    .put("accuracy", gps.getDouble("accuracy")).put("capturedAt", item.getString("capturedAt"))
                stage = "upload"
                val saved = api.webCall(token, "saveConsumerImage", JSONArray().put(payload))
                queue.update(id) { it.put("status", "uploaded").put("message", "Uploaded · original also safe on phone").put("image", saved) }
                Log.i(TAG, "Photo upload confirmed")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: UlaApiException) {
                Log.w(TAG, "Upload needs attention; stage=$stage")
                queue.update(id) { it.put("status", "attention").put("message", "Photo safe on phone · ${error.message}") }
            } catch (error: Exception) {
                // Never log tokens, image bytes, consumer details, GPS or exception messages.
                Log.w(TAG, "Upload deferred; stage=$stage errorClass=${error.javaClass.simpleName}")
                queue.update(id) { it.put("status", "pending").put("message", "Saved on phone · connection interrupted; upload will retry")
                    .put("lastFailureStage", stage).put("lastFailureClass", error.javaClass.simpleName) }
                retry = true
                break // A broken connection is shared; don't hammer the server for each photo.
            }
        }
        Log.i(TAG, "Upload drain ended; retry=$retry")
        if (retry) Result.retry() else Result.success()
    }

    private companion object { const val TAG = "UlaPhotoUpload" }
}
