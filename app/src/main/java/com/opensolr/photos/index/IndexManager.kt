package com.opensolr.photos.index

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
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
        api.createIndex(session, name, pickRegion(api.vectorRegions(session)), deviceName, deviceId)
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

    // silent: the region the platform flags as nearest, else the first one the configuration runs on
    private fun pickRegion(regions: List<VectorRegion>): String {
        if (regions.isEmpty()) throw ServiceException(AppText.s(R.string.err_no_region))
        regions.firstOrNull { it.nearest }?.let { return it.environment }
        return (regions.firstOrNull { versionAtLeast(it.solrVersion, OpensolrApi.MIN_SOLR) } ?: regions.first()).environment
    }

    private fun versionAtLeast(version: String, min: String): Boolean {
        val have = version.split('.').map { it.toIntOrNull() ?: 0 }
        val need = min.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(have.size, need.size)) {
            val h = have.getOrElse(i) { 0 }
            val n = need.getOrElse(i) { 0 }
            if (h != n) return h > n
        }
        return true
    }

    companion object {
        const val CONFIG_ASSET = "opensolr-photos-conf.zip"

        const val CONFIG_VERSION = 11
    }
}
