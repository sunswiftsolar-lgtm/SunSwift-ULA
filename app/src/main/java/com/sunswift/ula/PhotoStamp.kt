package com.sunswift.ula

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

object PhotoStamp {
    fun thumbnail(raw: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val options = BitmapFactory.Options().apply { var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2; inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: error("Phone photo cannot be read; original retained.")
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() } finally { bitmap.recycle() }
    }
    fun isoNow(millis: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    fun prepare(raw: ByteArray, item: JSONObject, address: String): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image cannot be decoded. Original retained on phone." }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
            inSampleSize = sample
        }
        var photo = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: error("Image cannot be decoded.")
        val orientation = ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (!transform.isIdentity) { val rotated = Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, transform, true); if (rotated !== photo) photo.recycle(); photo = rotated }
        val record = item.getJSONObject("record")
        val gps = item.getJSONObject("gps")
        val type = item.getString("imageType")
        val field = mapOf("panel1" to "panelNo1", "panel2" to "panelNo2", "inverter" to "inverterSerialNo")[type]
        val serial = field?.let { record.optString(it) }.orEmpty()
        val captured = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(item.getLong("capturedMillis")))
        val lines = listOf(type.replaceFirstChar { it.uppercase() } + if (serial.isBlank()) " Image" else " Serial No: $serial",
            "Consumer: ${record.optString("consumerNo")} · ${record.optString("consumerName")}",
            String.format(Locale.US, "GPS: %.6f, %.6f (±%.0fm)", gps.getDouble("latitude"), gps.getDouble("longitude"), gps.getDouble("accuracy")),
            "Location: $address", "Captured: $captured IST", "User: ${item.optString("ownerName")}")
        try {
            var width = minOf(1600, photo.width).coerceAtLeast(700)
            while (width >= 600) {
                val height = (photo.height.toDouble() / photo.width * width).toInt().coerceAtLeast(1)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = width / 60f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
                val lineHeight = paint.textSize * 1.45f
                val wrapped = lines.flatMap { line ->
                    val words = line.split(' '); val result = mutableListOf<String>(); var text = ""
                    for (word in words) { if (text.isNotEmpty() && paint.measureText("$text $word") > width - 32) { result += text; text = word } else text = if (text.isEmpty()) word else "$text $word" }
                    result + text
                }
                val footer = (wrapped.size * lineHeight + 28).toInt()
                val canvasBitmap = Bitmap.createBitmap(width, height + footer, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(canvasBitmap)
                    canvas.drawColor(Color.rgb(23, 50, 77)); canvas.drawBitmap(photo, null, Rect(0, 0, width, height), null)
                    wrapped.forEachIndexed { index, line -> canvas.drawText(line, 16f, height + 18f + paint.textSize + index * lineHeight, paint) }
                    for (quality in intArrayOf(85, 75, 65, 55, 45)) {
                        val output = ByteArrayOutputStream(); canvasBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                        if (output.size() <= 200 * 1024) return output.toByteArray()
                    }
                } finally { canvasBitmap.recycle() }
                width = (width * .85).toInt()
            }
            error("Photo could not be compressed below 200 KB. Original retained on phone.")
        } finally { photo.recycle() }
    }
}
