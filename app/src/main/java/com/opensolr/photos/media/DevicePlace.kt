package com.opensolr.photos.media

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

object DevicePlace {

    private const val FRESH_MS = 10 * 60 * 1000L

    private const val MAX_ACCURACY_M = 500f

    private const val FIX_TIMEOUT_MS = 15_000L

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Location is asked only while in use, so a sync running with the app out of sight never asks for a position.
    private fun appInSight(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    suspend fun now(context: Context): Location? {
        if (!permitted(context) || !appInSight()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val at = System.currentTimeMillis()
        val known = try {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
        } catch (e: SecurityException) {
            emptyList()
        }
        known.filter { usable(it) && abs(it.time - at) <= FRESH_MS }.maxByOrNull { it.time }?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                } catch (e: SecurityException) {
                    cont.resume(null)
                }
            }
        }
        return fresh?.takeIf { usable(it) }
    }

    private fun usable(location: Location): Boolean =
        (!location.hasAccuracy() || location.accuracy <= MAX_ACCURACY_M) &&
            !(location.latitude == 0.0 && location.longitude == 0.0)
}
