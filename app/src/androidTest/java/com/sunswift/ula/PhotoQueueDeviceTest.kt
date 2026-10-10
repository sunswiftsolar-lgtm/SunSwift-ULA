package com.sunswift.ula

import android.graphics.Bitmap
import android.location.Location
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoQueueDeviceTest {
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
