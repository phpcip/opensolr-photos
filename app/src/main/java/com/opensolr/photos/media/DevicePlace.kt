package com.opensolr.photos.media

import android.Manifest
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

/**
 * The phone's own position right now, for new photos that came without one (Cip, 2026-09-19): a
 * camera with no GPS, copied onto the phone. It fits only photos taken shortly before - see
 * SyncEngine - because the phone may be far from where they were taken by the time they arrive.
 */
object DevicePlace {

    /** How old a position the phone already knows may be and still count as "now". */
    private const val FRESH_MS = 10 * 60 * 1000L

    /** Least precision that still names the right village. */
    private const val MAX_ACCURACY_M = 500f

    /** How long a fresh position is waited for. */
    private const val FIX_TIMEOUT_MS = 15_000L

    /** True when the owner let the app know where the phone is. */
    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** True when that holds with the app in the background too, where the sync runs. */
    fun permittedInBackground(context: Context): Boolean =
        permitted(context) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    /**
     * Where the phone is now: the newest position it already knows when that is at most a few
     * minutes old, otherwise a fresh one, waited for briefly. Null without permission, without a
     * precise enough answer, or on an Android too old to ask for one.
     */
    suspend fun now(context: Context): Location? {
        if (!permitted(context)) return null
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

    /** Precise enough, and not the 0,0 of a receiver with no fix. */
    private fun usable(location: Location): Boolean =
        (!location.hasAccuracy() || location.accuracy <= MAX_ACCURACY_M) &&
            !(location.latitude == 0.0 && location.longitude == 0.0)
}
