package com.sunswift.ula

import android.graphics.Bitmap
import android.location.Location
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PhotoQueueDeviceTest {
    @Test fun upgradeResetsFailedScheduleButPreservesEncryptedOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PhotoQueue(context)
        val manager = WorkManager.getInstance(context)
        val owner = UlaUser("+910000000000", "Synthetic migration test", "EDITOR", false, true)
        val draft = queue.begin(JSONObject().put("consumerNo", "ULATESTB9002").put("consumerName", "Synthetic only"), "plant", owner)
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        queue.captureFile(draft).writeBytes(bytes)
        val saved = queue.confirmDraft()
        val id = saved.getString("id")
        try {
            val old = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS).build()
            manager.enqueueUniqueWork(PhotoQueue.WORK_NAME, ExistingWorkPolicy.REPLACE, old).result.get(10, TimeUnit.SECONDS)
            context.getSharedPreferences("ula_photo_scheduler", 0).edit().putInt("network_version", 1).commit()
            queue.schedule()
            // Observe the async migration; do not force the worker or bypass its constraints.
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var oldState = manager.getWorkInfoById(old.id).get(10, TimeUnit.SECONDS)?.state
            while (oldState != null && oldState != WorkInfo.State.CANCELLED && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(30)
                oldState = manager.getWorkInfoById(old.id).get(10, TimeUnit.SECONDS)?.state
            }
            val work = manager.getWorkInfosForUniqueWork(PhotoQueue.WORK_NAME).get(10, TimeUnit.SECONDS)
            assertTrue(oldState == null || oldState == WorkInfo.State.CANCELLED)
            assertTrue(work.any { it.id != old.id && it.state != WorkInfo.State.CANCELLED })
            assertEquals(2, context.getSharedPreferences("ula_photo_scheduler", 0).getInt("network_version", 0))
            assertArrayEquals(bytes, PhotoQueue(context).raw(saved))
            assertTrue(PhotoQueue(context).all().any { it.getString("id") == id })
        } finally {
            // Only this synthetic UUID in the separate debug package, never the production queue.
            for (suffix in listOf("raw", "json", "jpeg")) File(context.noBackupFilesDir, "offline-photos/$id.$suffix").delete()
        }
    }

    @Test fun durableEncryptedCapturesGpsAndCompression() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = PhotoQueue(context)
        val owner = UlaUser("+910000000000", "Synthetic device test", "EDITOR", false, true)
        val record = JSONObject().put("consumerNo", "ULATESTB9001").put("consumerName", "Synthetic only")
        val bitmap = Bitmap.createBitmap(1000, 750, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.LTGRAY)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray(); bitmap.recycle()
        val ids = mutableListOf<String>()
        try {
            for (type in listOf("panel1", "panel2")) {
                val draft = queue.begin(record, type, owner)
                queue.captureFile(draft).writeBytes(bytes)
                val item = queue.confirmDraft(); ids += item.getString("id")
                assertArrayEquals(bytes, PhotoQueue(context).raw(item))
                val encrypted = File(context.noBackupFilesDir, "offline-photos/${item.getString("id")}.raw").readBytes()
                assertFalse(bytes.contentEquals(encrypted)); assertEquals(12, encrypted[0].toInt())
            }
            val restored = PhotoQueue(context)
            assertTrue(ids.all { id -> restored.all().any { it.getString("id") == id } })
            assertEquals(0, restored.summary("another-user").getJSONArray("items").length())
            val stale = Location("synthetic").apply { latitude = 0.0; longitude = 0.0; accuracy = 10f; time = System.currentTimeMillis() - 600_000 }
            restored.attachLocation(stale)
            assertTrue(ids.all { id -> restored.all().first { it.getString("id") == id }.optJSONObject("gps") == null })
            val fresh = Location("synthetic").apply { latitude = 0.0; longitude = 0.0; accuracy = 20f; time = System.currentTimeMillis() }
            restored.attachLocation(fresh)
            for (id in ids) {
                val item = restored.all().first { it.getString("id") == id }
                assertTrue(item.getString("status") in setOf("pending", "login")) // The real worker may already have paused for this synthetic user's missing session.
                assertNotNull(item.optJSONObject("gps"))
                val jpeg = PhotoStamp.prepare(restored.raw(item), item, "Synthetic location")
                assertTrue(jpeg.size <= 204800)
                restored.savePrepared(item, jpeg)
                assertArrayEquals(jpeg, PhotoQueue(context).prepared(item))
                restored.update(id) { it.put("status", "uploaded") }
                assertArrayEquals(bytes, PhotoQueue(context).raw(item))
            }
        } finally {
            // Cleanup only these synthetic test UUIDs, in the separate debug package; never touch office data.
            for (id in ids) for (suffix in listOf("raw", "json", "jpeg")) File(context.noBackupFilesDir, "offline-photos/$id.$suffix").delete()
        }
    }
}
