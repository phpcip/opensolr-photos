package com.opensolr.photos.index

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.provider.Settings
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.IndexConnection
import com.opensolr.photos.data.Session
import com.opensolr.photos.net.AccountIndex
import com.opensolr.photos.net.IndexMissingException
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.OpensolrException
import com.opensolr.photos.net.ServiceException
import com.opensolr.photos.net.SignInRequiredException
import com.opensolr.photos.net.SolrClient
import com.opensolr.photos.net.VectorRegion
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.TimeZone

class IndexManager(
    private val context: Context,
    private val prefs: AppPrefs,
    private val api: OpensolrApi,
) {

    enum class Outcome {

        EXISTING,

        CREATED,

        RECREATED,

        NEEDS_REBUILD,

        CONFIG_NEWER,

        NEEDS_CHOICE,
    }

    var choices: List<AccountIndex> = emptyList()
        private set

    val deviceId: String
        @SuppressLint("HardwareIds")
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(32)

    val deviceName: String
        get() {
            val maker = Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty().trim()
            val base = listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ")
            val named = try { Settings.Global.getString(context.contentResolver, "device_name").orEmpty().trim() } catch (e: Exception) { "" }
            val extra = named.takeIf { it.isNotBlank() && !base.contains(it, ignoreCase = true) && !it.contains(model, ignoreCase = true) }
            return listOfNotNull(base.ifBlank { null }, extra).joinToString(" ").take(120).ifBlank { "Android phone" }
        }

    val indexName: String
        get() = prefs.chosenIndexName ?: ownIndexName

    val ownIndexName: String get() = "photos_${deviceId}__dense"

    suspend fun ensure(session: Session, prefetched: OpensolrApi.SyncInfo? = null, onStep: suspend (String) -> Unit = {}): Pair<IndexConnection, Outcome> {
        onStep(AppText.s(R.string.ix_looking))
        val name = indexName
        val account = prefetched?.indexes ?: api.indexes(session)
        if (account.any { it.name == name }) {
            if (prefs.chosenIndexName == null) prefs.chosenIndexName = name
            val connection = prefetched?.connectionFor(name) ?: connectionWithRetry(session, name)
            prefs.connection = connection

            val version = SolrClient(connection).configVersion()
            return connection to when {
                version < CONFIG_VERSION -> Outcome.NEEDS_REBUILD
                version > CONFIG_VERSION -> Outcome.CONFIG_NEWER
                else -> Outcome.EXISTING
            }
        }

        if (prefs.chosenIndexName == null) {
            val others = account.filter { it.isPhotos }
            if (others.isNotEmpty()) {
                choices = others
                return IndexConnection(name, "", "", "", "") to Outcome.NEEDS_CHOICE
            }
        }

        val hadIndex = prefs.knownIndexName == name
        prefs.connection = null
        onStep(AppText.s(R.string.ix_creating))
        api.createIndex(session, name, pickRegion(api.vectorRegions(session), lastKnownLocation()), deviceName, deviceId)
        prefs.chosenIndexName = name
        onStep(AppText.s(R.string.ix_setting_up))
        val connection = connectionWithRetry(session, name)
        api.uploadConfig(session, name, configZip())
        waitForSchema(connection)
        prefs.connection = connection
        return connection to if (hadIndex) Outcome.RECREATED else Outcome.CREATED
    }

    suspend fun applyConfig(session: Session, connection: IndexConnection, onStep: suspend (String) -> Unit = {}) {
        onStep(AppText.s(R.string.ix_updating))
        api.uploadConfig(session, connection.indexName, configZip())
        waitForSchema(connection)
    }

    suspend fun configVersionOf(connection: IndexConnection): Int = SolrClient(connection).configVersion()

    suspend fun refreshConnection(session: Session): IndexConnection {
        val connection = api.connection(session, indexName)
        prefs.connection = connection
        return connection
    }

    private suspend fun connectionWithRetry(session: Session, name: String): IndexConnection {
        var last: Exception? = null
        repeat(6) { attempt ->
            try {
                return api.connection(session, name)
            } catch (e: IndexMissingException) {
                last = e
            } catch (e: ServiceException) {
                last = e
            }
            delay(2000L * (attempt + 1))
        }
        throw last ?: ServiceException(AppText.s(R.string.err_not_reachable))
    }

    private suspend fun waitForSchema(connection: IndexConnection) {
        val solr = SolrClient(connection)
        repeat(12) {
            try {
                if (solr.hasPhotoSchema() && solr.configVersion() == CONFIG_VERSION) return
            } catch (e: SignInRequiredException) {
                throw e
            } catch (e: OpensolrException) {
            } catch (e: IOException) {
            }
            delay(3000)
        }
        throw ServiceException(AppText.s(R.string.err_config_not_live))
    }

    private fun configZip(): ByteArray = context.assets.open(CONFIG_ASSET).use { it.readBytes() }

    private fun pickRegion(regions: List<VectorRegion>, location: Location?): String {
        if (regions.isEmpty()) throw ServiceException(AppText.s(R.string.err_no_region))
        val americas = location?.let { it.longitude in -170.0..-30.0 } ?: TimeZone.getDefault().id.startsWith("America/")
        val wanted = if (americas) REGION_AMERICAS else REGION_EUROPE
        regions.firstOrNull { it.environment.equals(wanted, ignoreCase = true) }?.let { return it.environment }
        val newest = regions.filter { it.solrVersion.startsWith("9.6") }.ifEmpty { regions }
        val preferred = newest.firstOrNull { (it.country == "USA") == americas } ?: newest.first()
        return preferred.environment
    }

    private fun lastKnownLocation(): Location? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    companion object {
        const val CONFIG_ASSET = "opensolr-photos-conf.zip"

        const val CONFIG_VERSION = 11

        const val REGION_AMERICAS = "CHICAGO-96"

        const val REGION_EUROPE = "FINLAND9"
    }
}
