package com.sunswift.ula

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Foreground-only capture-time fixes; background workers never sample later location. */
class CaptureGps(context: Context, private val queue: PhotoQueue, private val scope: CoroutineScope) : LocationListener {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var latest: Location? = null

    fun start() {
        if (queue.draft() == null && queue.all().none { it.optJSONObject("gps") == null && System.currentTimeMillis() - it.optLong("capturedMillis") <= PhotoQueue.GPS_WINDOW_MS }) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        stop()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (manager.isProviderEnabled(provider)) {
                    manager.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
                    manager.requestLocationUpdates(provider, 1000, 0f, this, Looper.getMainLooper())
                }
            }
        }
    }
    fun attachLatest() { latest?.let { location -> scope.launch(Dispatchers.IO) {
        queue.attachLocation(location)
        if (queue.draft() == null && queue.all().none { it.optJSONObject("gps") == null && System.currentTimeMillis() - it.optLong("capturedMillis") <= PhotoQueue.GPS_WINDOW_MS }) stop()
    } } }
    fun stop() { runCatching { manager.removeUpdates(this) } }
    override fun onLocationChanged(location: Location) {
        if (System.currentTimeMillis() - location.time > PhotoQueue.GPS_WINDOW_MS) return
        if (latest == null || location.time > latest!!.time) latest = Location(location)
        attachLatest()
    }
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
