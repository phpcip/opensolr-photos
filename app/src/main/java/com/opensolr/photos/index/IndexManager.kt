package com.opensolr.photos.index

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
        /** The index runs an older configuration than this app ships: it must be rebuilt. */
        NEEDS_REBUILD,
        /** The index runs a newer configuration than this app: the app must be updated. */
        CONFIG_NEWER,
        /** This phone has no index, but the account has photo indexes: the owner must say
         *  which of those phones this one is, or that it is a new one. See [choices]. */
        NEEDS_CHOICE,
    }

    /** The account's photo indexes offered when [Outcome.NEEDS_CHOICE] was returned. */
    var choices: List<AccountIndex> = emptyList()
        private set

    /**
     * The identifier Android gives this app on this phone: the same across reinstalls and
     * updates, different after a factory reset or on another phone.
     */
    val deviceId: String
        @SuppressLint("HardwareIds")
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(32)

    /**
     * The phone as the owner would recognise it: maker and model, then the name the phone
     * carries in its settings when it adds something ("Samsung SM-S918B Galaxy S23 Ultra").
     */
    val deviceName: String
        get() {
            val maker = Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty().trim()
            val base = listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ")
            val named = try { Settings.Global.getString(context.contentResolver, "device_name").orEmpty().trim() } catch (e: Exception) { "" }
            val extra = named.takeIf { it.isNotBlank() && !base.contains(it, ignoreCase = true) && !it.contains(model, ignoreCase = true) }
            return listOfNotNull(base.ifBlank { null }, extra).joinToString(" ").take(120).ifBlank { "Android phone" }
        }

    /**
     * The index this phone uses: the one chosen once (created here, or picked as "this
     * device"), else the name derived from the phone's identifier.
     */
    val indexName: String
        get() = prefs.chosenIndexName ?: ownIndexName

    /** The name this phone would create for itself. */
    val ownIndexName: String get() = "photos_${deviceId}__dense"

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
        val account = api.indexes(session)
        if (account.any { it.name == name }) {
            if (prefs.chosenIndexName == null) prefs.chosenIndexName = name
            val connection = connectionWithRetry(session, name)
            prefs.connection = connection
            // The configuration version lives in the index; compared with the one in the APK.
            val version = SolrClient(connection).configVersion()
            return connection to when {
                version < CONFIG_VERSION -> Outcome.NEEDS_REBUILD
                version > CONFIG_VERSION -> Outcome.CONFIG_NEWER
                else -> Outcome.EXISTING
            }
        }

        // A phone with no index of its own, on an account that already holds photos of other
        // phones (or of this one before a reset): the owner decides before anything is created.
        if (prefs.chosenIndexName == null) {
            val others = account.filter { it.isPhotos }
            if (others.isNotEmpty()) {
                choices = others
                return IndexConnection(name, "", "", "", "") to Outcome.NEEDS_CHOICE
            }
        }

        val hadIndex = prefs.knownIndexName == name
        prefs.connection = null
        onStep("Creating this phone's index")
        api.createIndex(session, name, pickRegion(api.vectorRegions(session), lastKnownLocation()), deviceName, deviceId)
        prefs.chosenIndexName = name
        onStep("Setting up the index")
        val connection = connectionWithRetry(session, name)
        api.uploadConfig(session, name, configZip())
        waitForSchema(connection)
        prefs.connection = connection
        return connection to if (hadIndex) Outcome.RECREATED else Outcome.CREATED
    }

    /**
     * Uploads the app's configuration to the index and waits until the index runs it. Called
     * by a rebuild only AFTER the index was emptied: documents written under the old schema
     * never meet the new one (Cip, 2026-09-15).
     */
    suspend fun applyConfig(session: Session, connection: IndexConnection, onStep: suspend (String) -> Unit = {}) {
        onStep("Updating the index configuration")
        api.uploadConfig(session, connection.indexName, configZip())
        waitForSchema(connection)
    }

    /**
     * The version the index reports, or 0 when it has none. A quick check for the app start.
     */
    suspend fun configVersionOf(connection: IndexConnection): Int = SolrClient(connection).configVersion()

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
                if (solr.hasPhotoSchema() && solr.configVersion() == CONFIG_VERSION) return
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
     * Chooses where to create the index, by the phone's position: [REGION_AMERICAS] for a phone
     * in the Americas, [REGION_EUROPE] for everywhere else. Without a position (permission not
     * given, or no fix yet) the time zone decides. When the chosen environment is not offered,
     * the newest one on the matching continent is used, and failing that any vector environment.
     */
    private fun pickRegion(regions: List<VectorRegion>, location: Location?): String {
        if (regions.isEmpty()) throw ServiceException("No Opensolr environment with vector search is available right now")
        val americas = location?.let { it.longitude in -170.0..-30.0 } ?: TimeZone.getDefault().id.startsWith("America/")
        val wanted = if (americas) REGION_AMERICAS else REGION_EUROPE
        regions.firstOrNull { it.environment.equals(wanted, ignoreCase = true) }?.let { return it.environment }
        val newest = regions.filter { it.solrVersion.startsWith("9.6") }.ifEmpty { regions }
        val preferred = newest.firstOrNull { (it.country == "USA") == americas } ?: newest.first()
        return preferred.environment
    }

    /**
     * The phone's last known position, from any provider, when the user allowed coarse location.
     * Null without the permission or without a fix. Never asks the user for anything here.
     */
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

        /**
         * The version of the bundled solr/conf. Must equal config_version in solrconfig.xml;
         * raise both whenever any file in solr/conf changes. An index on a lower version is
         * rebuilt (with the owner's consent), one on a higher version asks for an app update.
         */
        const val CONFIG_VERSION = 10
        /** Opensolr environment for phones in the Americas. */
        const val REGION_AMERICAS = "CHICAGO-96"
        /** Opensolr environment for everyone else. */
        const val REGION_EUROPE = "FINLAND9"
    }
}
