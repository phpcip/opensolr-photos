package com.opensolr.photos.index

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.IndexConnection
import com.opensolr.photos.data.Session
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

/**
 * Owns the phone's Opensolr Index: its name, finding it, creating it, and setting it up.
 *
 * One phone, one index, named photos_<ANDROID_ID>__dense. ANDROID_ID stays the same across
 * reinstalls of the app on the same phone and differs on every other phone, so a reinstall finds
 * its old index (and only re-syncs) while a new phone gets an index of its own.
 */
class IndexManager(
    private val context: Context,
    private val prefs: AppPrefs,
    private val api: OpensolrApi,
) {

    /**
     * What [ensure] found.
     */
    enum class Outcome {
        /** The index was already in the account. */
        EXISTING,
        /** The index did not exist and this phone never had one: first setup. */
        CREATED,
        /** This phone had an index, it was gone, and it was created again empty. */
        RECREATED,
    }

    /**
     * photos_<ANDROID_ID>__dense.
     */
    val indexName: String
        @SuppressLint("HardwareIds")
        get() {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                .orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(32)
            return "photos_${androidId}__dense"
        }

    /**
     * Makes sure the index exists, carries this app's schema, and that its connection is saved.
     *
     * A missing index is only created after the account's index list was read successfully and
     * did not contain it. A network error, a server error or a refused key never lead to a
     * create: they surface as exceptions, and the next sync simply tries again.
     */
    suspend fun ensure(session: Session, onStep: suspend (String) -> Unit = {}): Pair<IndexConnection, Outcome> {
        onStep("Looking for this phone's index")
        val name = indexName
        if (name in api.indexNames(session)) {
            val connection = connectionWithRetry(session, name)
            if (!SolrClient(connection).hasPhotoSchema()) {
                onStep("Setting up the index")
                api.uploadConfig(session, name, configZip())
                waitForSchema(connection)
            }
            prefs.connection = connection
            return connection to Outcome.EXISTING
        }

        val hadIndex = prefs.knownIndexName == name
        prefs.connection = null
        onStep("Creating this phone's index")
        api.createIndex(session, name, pickRegion(api.vectorRegions(session)))
        onStep("Setting up the index")
        val connection = connectionWithRetry(session, name)
        api.uploadConfig(session, name, configZip())
        waitForSchema(connection)
        prefs.connection = connection
        return connection to if (hadIndex) Outcome.RECREATED else Outcome.CREATED
    }

    /**
     * Re-reads the index address and password from the account (after the index refused its
     * saved password) and saves them.
     */
    suspend fun refreshConnection(session: Session): IndexConnection {
        val connection = api.connection(session, indexName)
        prefs.connection = connection
        return connection
    }

    /**
     * get_core_info right after a create can race the platform finishing the create, so a few
     * patient retries are allowed before giving up.
     */
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
        throw last ?: ServiceException("The index did not become reachable")
    }

    /**
     * Waits until the uploaded schema is live. Right after a create or an upload the platform is
     * still reloading the index, applying its password and opening its firewall rule, so for a
     * few seconds the index may answer 401, 403 or 404; all of those are waited out here instead
     * of being mistaken for a changed password or a plan limit.
     */
    private suspend fun waitForSchema(connection: IndexConnection) {
        val solr = SolrClient(connection)
        repeat(12) {
            try {
                if (solr.hasPhotoSchema()) return
            } catch (e: SignInRequiredException) {
                throw e
            } catch (e: OpensolrException) {
            } catch (e: IOException) {
            }
            delay(3000)
        }
        throw ServiceException("The index configuration did not take effect. Try Sync again in a minute.")
    }

    /**
     * The zipped Solr configuration built from solr/conf at compile time.
     */
    private fun configZip(): ByteArray = context.assets.open(CONFIG_ASSET).use { it.readBytes() }

    /**
     * Chooses where to create the index: an environment on the newest Solr, in the United States
     * for phones set to an American time zone and in Europe for everyone else.
     */
    private fun pickRegion(regions: List<VectorRegion>): String {
        if (regions.isEmpty()) throw ServiceException("No Opensolr environment with vector search is available right now")
        val newest = regions.filter { it.solrVersion.startsWith("9.6") }.ifEmpty { regions }
        val americas = TimeZone.getDefault().id.startsWith("America/")
        val preferred = newest.firstOrNull { (it.country == "USA") == americas } ?: newest.first()
        return preferred.environment
    }

    companion object {
        const val CONFIG_ASSET = "opensolr-photos-conf.zip"
    }
}
