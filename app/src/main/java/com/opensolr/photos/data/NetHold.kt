package com.opensolr.photos.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.opensolr.photos.ui.Actions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Mobile data with "Use mobile data" off: nothing syncs, nothing asks the server, nothing shows as syncing. */
object NetHold {

    private val _held = MutableStateFlow(false)
    val held: StateFlow<Boolean> = _held
    private var started = false

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        check(app)
        if (started) return
        started = true
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = set(app, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                override fun onLost(network: Network) = check(app)
            })
        }
    }

    /** Read on the spot, for a run that starts with no app open. */
    fun heldNow(context: Context): Boolean = Actions.onMeteredNetwork(context) && !AppPrefs(context).useMobileData

    fun check(context: Context) = set(context.applicationContext, Actions.onMeteredNetwork(context))

    private fun set(app: Context, metered: Boolean) {
        val now = metered && !AppPrefs(app).useMobileData
        val was = _held.getAndUpdate(now)
        if (!now) runCatching { com.opensolr.photos.sync.Notifier.waitingWifiDone(app) }
        // what waited for Wi-Fi goes now
        if (was && !now) Thread {
            runCatching { kotlinx.coroutines.runBlocking { com.opensolr.photos.storage.Deletions.push(app); com.opensolr.photos.storage.Deletions.pull(app) } }
            runCatching { kotlinx.coroutines.runBlocking { com.opensolr.photos.search.IndexChanges.pull(app) } }
        }.start()
    }

    private fun MutableStateFlow<Boolean>.getAndUpdate(next: Boolean): Boolean { val old = value; value = next; return old }
}
