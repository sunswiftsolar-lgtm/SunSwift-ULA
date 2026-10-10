package com.sunswift.ula

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Visible, capture-scoped location service. Starts only from the user's foreground photo workflow. */
class CaptureLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var queue: PhotoQueue
    private lateinit var gps: CaptureGps
    private var watchdog: Job? = null
    override fun onCreate() {
        super.onCreate(); queue = PhotoQueue(this); gps = CaptureGps(this, queue, scope)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasPermission(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "ULA photo GPS", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("ULA capture-time GPS").setContentText("Recording GPS for your photos. Uploads do not block the next capture.")
            .setContentIntent(open).setOngoing(true).build()
        try { ServiceCompat.startForeground(this, 1701, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
        catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        gps.start()
        watchdog?.cancel()
        watchdog = scope.launch {
            val started = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(3000)
                val needGps = withContext(Dispatchers.IO) { queue.draft() != null || queue.all().any { it.optJSONObject("gps") == null && System.currentTimeMillis() - it.optLong("capturedMillis") <= PhotoQueue.GPS_WINDOW_MS } }
                if (!needGps || SystemClock.elapsedRealtime() - started > 15 * 60_000L) { stopSelf(); break }
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { watchdog?.cancel(); gps.stop(); scope.cancel(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); super.onDestroy() }
    companion object {
        private const val CHANNEL = "ula-photo-gps"
        private fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun start(context: Context) {
            if (hasPermission(context)) runCatching { ContextCompat.startForegroundService(context, Intent(context, CaptureLocationService::class.java)) }
        }
    }
}
