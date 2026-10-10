package com.sunswift.ula

import android.content.Context
import android.location.Location
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, Keystore-encrypted originals. Never put image bytes or credentials in WorkManager Data. */
class PhotoQueue(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "offline-photos").apply { mkdirs() }
    private val staging = File(context.filesDir, "photo-staging").apply { mkdirs() }

    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(cipher.iv.size); stream.write(cipher.iv); stream.write(cipher.doFinal(bytes)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private fun read(file: File): ByteArray {
        val bytes = AtomicFile(file).readFully()
        val ivSize = bytes[0].toInt() and 255
        require(ivSize == 12 && bytes.size > 1 + ivSize + 16) { "Saved photo is damaged; the original has not been deleted." }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivSize)))
            doFinal(bytes.copyOfRange(1 + ivSize, bytes.size))
        }
    }

    private fun itemFile(id: String, suffix: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id))
        return File(root, "$id.$suffix")
    }

    fun draft(): JSONObject? = synchronized(lock) {
        val file = File(root, "capture-draft.json")
        if (!file.exists()) null else JSONObject(String(read(file), Charsets.UTF_8))
    }

    fun begin(record: JSONObject, type: String, user: UlaUser): JSONObject = synchronized(lock) {
        require(type in TYPES) { "Unknown photo type." }
        require(Regex("[A-Z0-9]{12}").matches(record.optString("consumerNo"))) { "Save and reopen the consumer before taking photos." }
        require(draft() == null) { "Finish the previous camera capture first." }
        require(staging.usableSpace > 100L * 1024 * 1024) { "Phone storage is low. Free space before taking another photo; existing photos are safe." }
        val data = JSONObject().put("id", UUID.randomUUID().toString()).put("record", record).put("imageType", type)
            .put("ownerMobile", user.mobile).put("ownerName", user.name).put("startedAt", System.currentTimeMillis())
        write(File(root, "capture-draft.json"), data.toString().toByteArray())
        data
    }

    fun captureFile(item: JSONObject): File = File(staging, "${item.getString("id")}.jpg")

    fun cancelDraft() = synchronized(lock) {
        // Only the current, unconfirmed camera draft is removed, never a confirmed queued photo.
        draft()?.let { captureFile(it).delete() }
        AtomicFile(File(root, "capture-draft.json")).delete()
    }

    fun confirmDraft(source: android.net.Uri? = null): JSONObject = synchronized(lock) {
        val item = draft() ?: error("Camera details could not be restored.")
        val raw = if (source != null) context.contentResolver.openInputStream(source)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count)
                require(output.size() <= MAX_RAW_BYTES) { "Image exceeds 40 MB; choose a smaller image." } }
            val bytes = output.toByteArray()
            require(bytes.size <= MAX_RAW_BYTES) { "Image exceeds 40 MB; choose a smaller image." }
            bytes
        } ?: error("Selected image cannot be read.") else captureFile(item).let { file ->
            require(file.length() <= MAX_RAW_BYTES) { "Image exceeds 40 MB; the capture draft remains on phone." }; file.readBytes() }
        require(raw.isNotEmpty() && raw.size <= MAX_RAW_BYTES) { "Camera image is empty or too large. The capture draft remains on the phone." }
        val id = item.getString("id")
        if (!itemFile(id, "raw").exists()) write(itemFile(id, "raw"), raw)
        // Persist the original before acknowledging capture or releasing the next camera button.
        val capturedMillis = if (source == null) captureFile(item).lastModified().takeIf { it >= item.optLong("startedAt") } ?: item.getLong("startedAt") else System.currentTimeMillis()
        val fix = item.optJSONObject("gps")
        if (fix != null && kotlin.math.abs(fix.optLong("fixTime") - capturedMillis) > GPS_WINDOW_MS) item.remove("gps")
        item.put("capturedAt", PhotoStamp.isoNow(capturedMillis)).put("capturedMillis", capturedMillis)
            .put("status", if (item.optJSONObject("gps") == null) "gps" else "pending")
            .put("message", if (item.optJSONObject("gps") == null) "Saved on phone · obtaining capture-time GPS" else "Saved on phone · waiting for connection/upload")
        write(itemFile(id, "json"), item.toString().toByteArray())
        cancelDraft()
        schedule()
        item
    }

    fun all(): List<JSONObject> = synchronized(lock) {
        root.listFiles()?.filter { it.extension == "json" && it.name != "capture-draft.json" }?.mapNotNull {
            runCatching { JSONObject(String(read(it), Charsets.UTF_8)) }.getOrNull()
        }?.sortedBy { it.optLong("capturedMillis") } ?: emptyList()
    }

    fun update(id: String, block: (JSONObject) -> Unit): JSONObject = synchronized(lock) {
        val item = JSONObject(String(read(itemFile(id, "json")), Charsets.UTF_8))
        block(item)
        write(itemFile(id, "json"), item.toString().toByteArray())
        item
    }

    fun attachLocation(location: Location) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() || !location.hasAccuracy()) return
        synchronized(lock) {
            draft()?.let { data ->
                if (System.currentTimeMillis() - location.time <= GPS_WINDOW_MS) {
                    data.put("gps", JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
                        .put("accuracy", location.accuracy.toDouble()).put("fixTime", location.time))
                    write(File(root, "capture-draft.json"), data.toString().toByteArray())
                }
            }
        }
        all().filter { it.optJSONObject("gps") == null && it.optString("status") == "gps" }.forEach { item ->
            // Never substitute the upload-time location after the user has moved away.
            if (kotlin.math.abs(location.time - item.optLong("capturedMillis")) <= GPS_WINDOW_MS) {
                update(item.getString("id")) {
                    it.put("gps", JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
                        .put("accuracy", location.accuracy.toDouble()).put("fixTime", location.time))
                    it.put("status", "pending").put("message", "Saved on phone · waiting for connection/upload")
                }
                schedule()
            }
        }
    }

    fun raw(item: JSONObject): ByteArray = read(itemFile(item.getString("id"), "raw"))
    fun prepared(item: JSONObject): ByteArray? = itemFile(item.getString("id"), "jpeg").let { if (it.exists()) read(it) else null }
    fun savePrepared(item: JSONObject, bytes: ByteArray) = write(itemFile(item.getString("id"), "jpeg"), bytes)

    fun summary(owner: String): JSONObject {
        val items = JSONArray()
        all().filter { it.optString("ownerMobile") == owner }.forEach { item ->
            val status = item.optString("status")
            val message = if (status == "gps" && System.currentTimeMillis() - item.optLong("capturedMillis") > GPS_WINDOW_MS)
                "Photo safe on phone · capture-time GPS unavailable; recapture outdoors" else item.optString("message")
            items.put(JSONObject().put("id", item.getString("id")).put("consumerNo", item.getJSONObject("record").optString("consumerNo"))
                .put("imageType", item.optString("imageType")).put("capturedAt", item.optString("capturedAt"))
                .put("status", status).put("message", message).put("image", item.optJSONObject("image")))
        }
        return JSONObject().put("items", items)
    }

    fun schedule() {
        val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        private val lock = Any()
        private const val KEY_ALIAS = "ula_offline_photos_v1"
        const val WORK_NAME = "ula-offline-photo-upload"
        const val GPS_WINDOW_MS = 120_000L
        const val MAX_RAW_BYTES = 40 * 1024 * 1024
        val TYPES = setOf("panel1", "panel2", "inverter", "plant")
    }
}
