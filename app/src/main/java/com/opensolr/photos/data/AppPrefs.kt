package com.opensolr.photos.data

import android.content.Context
import org.json.JSONObject

class AppPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var session: Session?
        get() {
            val email = prefs.getString(KEY_EMAIL, null) ?: return null
            val key = prefs.getString(KEY_API_KEY, null)?.let { SecureStore.decrypt(it) } ?: return null
            return Session(email, key)
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_EMAIL)
                   .remove(KEY_API_KEY)
            .remove(KEY_DEVICE_KEY)
                } else {
                    putString(KEY_EMAIL, value.email)
                    putString(KEY_API_KEY, SecureStore.encrypt(value.apiKey))
                }
            }.commit()
        }

    /** True when the key held is this phone's own, revocable from Account > Devices; false for the account key of older sign-ins. */
    var deviceKey: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_KEY, false)
        set(value) { prefs.edit().putBoolean(KEY_DEVICE_KEY, value).commit() }

    var connection: IndexConnection?
        get() {
            val name = prefs.getString(KEY_INDEX, null) ?: return null
            val url = prefs.getString(KEY_SOLR_URL, null) ?: return null
            val user = prefs.getString(KEY_SOLR_USER, null) ?: return null
            val password = prefs.getString(KEY_SOLR_PASSWORD, null)?.let { SecureStore.decrypt(it) } ?: return null
            return IndexConnection(name, url, user, password, prefs.getString(KEY_ENVIRONMENT, "") ?: "")
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_SOLR_URL)
                    remove(KEY_SOLR_USER)
                    remove(KEY_SOLR_PASSWORD)
                    remove(KEY_ENVIRONMENT)
                } else {
                    putString(KEY_INDEX, value.indexName)
                    putString(KEY_SOLR_URL, value.baseUrl)
                    putString(KEY_SOLR_USER, value.username)
                    putString(KEY_SOLR_PASSWORD, SecureStore.encrypt(value.password))
                    putString(KEY_ENVIRONMENT, value.environment)
                }
            }.commit()
        }

    val knownIndexName: String? get() = prefs.getString(KEY_INDEX, null)

    var account: AccountLimits?
        get() = prefs.getString(KEY_ACCOUNT, null)?.let {
            try {
                AccountLimits.fromJson(JSONObject(it))
            } catch (e: Exception) {
                null
            }
        }
        set(value) {
            prefs.edit().putString(KEY_ACCOUNT, value?.toJson()).apply()
        }

    var folders: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_FOLDERS, value.toSet()).putBoolean(KEY_FOLDERS_CHOSEN, true).commit()
        }

    val foldersChosen: Boolean get() = prefs.getBoolean(KEY_FOLDERS_CHOSEN, false)

    var schedule: SyncSchedule
        get() = runCatching { SyncSchedule.valueOf(prefs.getString(KEY_SCHEDULE, SyncSchedule.WEEKLY.name)!!) }
            .getOrDefault(SyncSchedule.WEEKLY)
        set(value) {
            prefs.edit().putString(KEY_SCHEDULE, value.name).apply()
        }

    var lastReport: SyncReport?
        get() = SyncReport.fromJson(prefs.getString(KEY_REPORT, null))
        set(value) {
            prefs.edit().putString(KEY_REPORT, value?.toJson()).commit()
        }

    /** Photo ids follow the path inside the volume (not the mount point) since this was set: the clone was moved over once. */
    var idsRelative: Boolean
        get() = prefs.getBoolean("ids_relative", false)
        set(value) { prefs.edit().putBoolean("ids_relative", value).commit() }

    var clipAliasesChecked: Boolean
        get() = prefs.getBoolean("clip_aliases_checked", false)
        set(value) { prefs.edit().putBoolean("clip_aliases_checked", value).apply() }

    /** Photos gone from the phone in numbers: the owner said to take them out of the index. */
    var removeMissingApproved: Boolean
        get() = prefs.getBoolean("remove_missing_ok", false)
        set(value) { prefs.edit().putBoolean("remove_missing_ok", value).commit() }

    /** How many indexed photos the last sync found gone from the phone and left in the index, waiting for the owner. */
    var missingHeld: Int
        get() = prefs.getInt("missing_held", 0)
        set(value) { prefs.edit().putInt("missing_held", value).commit() }

    /** The owner pressed Stop: nothing starts on its own until they press Sync, Reset or any other action. */
    var syncPaused: Boolean
        get() = prefs.getBoolean("sync_paused", false)
        set(value) { prefs.edit().putBoolean("sync_paused", value).commit() }

    /** How far the refill from the clone got (the last document id written): it goes on from there, never from the start. */
    var restoreAfter: String
        get() = prefs.getString("restore_after", "") ?: ""
        set(value) { prefs.edit().putString("restore_after", value).commit() }

    /** Reset: the index was emptied and is to be filled again from the local clone at the next sync. */
    var restoreFromClone: Boolean
        get() = prefs.getBoolean("restore_from_clone", false)
        set(value) { prefs.edit().putBoolean("restore_from_clone", value).commit() }

    var rebuildApproved: Boolean
        get() = prefs.getBoolean(KEY_REBUILD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REBUILD, value).commit()
        }

    var rereadAllSince: Long
        get() = prefs.getLong(KEY_REREAD_ALL_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_REREAD_ALL_SINCE, value).apply()

    var resyncIds: Set<String>
        get() = prefs.getStringSet(KEY_RESYNC_IDS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_RESYNC_IDS, value.toSet()).commit()
        }

    // Copies saved from the editor, "copyId|originalId": their faces take the original's names when first read
    var copyOrigins: Set<String>
        get() = prefs.getStringSet(KEY_COPY_ORIGINS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_COPY_ORIGINS, value.toSet()).commit()
        }

    // Photos whose owner reset their wording: read again, and the server drops the stored wording
    var wordingResetIds: Set<String>
        get() = prefs.getStringSet(KEY_WORDING_RESET_IDS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_WORDING_RESET_IDS, value.toSet()).commit()
        }

    var chosenIndexName: String?
        get() = prefs.getString(KEY_CHOSEN_INDEX, null)
        set(value) {
            prefs.edit().putString(KEY_CHOSEN_INDEX, value).commit()
        }

    var warnedKeys: Set<String>
        get() = prefs.getStringSet(KEY_WARNED, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_WARNED, value.toSet()).commit()
        }

    var folderStamp: String?
        get() = prefs.getString(KEY_FOLDER_STAMP, null)
        set(value) {
            prefs.edit().putString(KEY_FOLDER_STAMP, value).apply()
        }

    // "index|config version|millis" of the last full index check that passed; the quick sync leans on it
    var indexChecked: String?
        get() = prefs.getString(KEY_INDEX_CHECKED, null)
        set(value) {
            prefs.edit().putString(KEY_INDEX_CHECKED, value).apply()
        }

    var lastWatchSyncAt: Long
        get() = prefs.getLong(KEY_WATCH_SYNC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_WATCH_SYNC, value).apply()
        }

    var updateCheckedAt: Long
        get() = prefs.getLong(KEY_UPDATE_CHECKED, 0L)
        set(value) {
            prefs.edit().putLong(KEY_UPDATE_CHECKED, value).apply()
        }

    var updateDismissed: String?
        get() = prefs.getString(KEY_UPDATE_DISMISSED, null)
        set(value) {
            prefs.edit().putString(KEY_UPDATE_DISMISSED, value).apply()
        }

    var collapsedHeadings: Map<String, Set<String>>
        get() = prefs.getStringSet(KEY_COLLAPSED, emptySet()).orEmpty().mapNotNull { line ->
            val at = line.indexOf('\u0001')
            if (at <= 0) null else line.substring(0, at) to
                line.substring(at + 1).split('\u0002').filter { it.isNotEmpty() }.toSet()
        }.toMap()
        set(value) {
            prefs.edit().putStringSet(
                KEY_COLLAPSED,
                value.filterValues { it.isNotEmpty() }
                    .map { (context, keys) -> context + '\u0001' + keys.joinToString("\u0002") }
                    .toSet(),
            ).apply()
        }

    var openFilterSections: Set<String>
        get() = prefs.getStringSet(KEY_OPEN_FILTERS, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_OPEN_FILTERS, value).apply()

    /** Zones and sections of the Me screen that are open. */
    var meZonesOpen: Set<String>
        get() = prefs.getStringSet(KEY_ME_ZONES_OPEN, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_ME_ZONES_OPEN, value).apply()

    var statsOpen: Set<String>
        get() = prefs.getStringSet(KEY_STATS_OPEN, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_STATS_OPEN, value).apply()

    var foldedAlbumSections: Set<String>
        get() = prefs.getStringSet(KEY_FOLDED_ALBUMS, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_FOLDED_ALBUMS, value).apply()

    var cloneComplete: Boolean
        get() = prefs.getBoolean(KEY_CLONE_COMPLETE, false) && cloneFormat == CLONE_FORMAT
        set(value) {
            prefs.edit().putBoolean(KEY_CLONE_COMPLETE, value).putInt(KEY_CLONE_FORMAT, CLONE_FORMAT).commit()
        }

    private val cloneFormat: Int get() = prefs.getInt(KEY_CLONE_FORMAT, 0)

    var facetsJson: String?
        get() = prefs.getString(KEY_FACETS, null)
        set(value) = prefs.edit().putString(KEY_FACETS, value).apply()

    // off until the owner turns it on; the key changed on 09/25/2026 so every phone starts off again
    var autoPlaceSince: Long
        get() = prefs.getLong(KEY_AUTO_PLACE_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_AUTO_PLACE_SINCE, value).apply()

    var groupBy: String
        get() = prefs.getString(KEY_GROUP_BY, "relevance") ?: "relevance"
        set(value) = prefs.edit().putString(KEY_GROUP_BY, value).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var lexicalWeight: Float
        get() = prefs.getFloat(KEY_LEXICAL_WEIGHT, DEFAULT_LEXICAL_WEIGHT).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_LEXICAL_WEIGHT, value.coerceIn(0f, 1f)).apply()

    /** How many of the typed words must match: 0 flexible, 1 balanced, 2 strict. */
    var matchLevel: Int
        get() = prefs.getInt(KEY_MATCH_LEVEL, DEFAULT_MATCH_LEVEL).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_MATCH_LEVEL, value.coerceIn(0, 2)).apply()

    var cacheSeconds: Int
        get() = prefs.getInt(KEY_CACHE_SECONDS, SearchCache.DEFAULT_SECONDS)
            .coerceIn(SearchCache.MIN_SECONDS, SearchCache.MAX_SECONDS)
        set(value) {
            prefs.edit().putInt(KEY_CACHE_SECONDS, value.coerceIn(SearchCache.MIN_SECONDS, SearchCache.MAX_SECONDS)).apply()
        }

    /** Videos in the chosen folders are indexed too (their first frame); on unless the owner turns it off. */
    var includeVideos: Boolean
        get() = prefs.getBoolean(KEY_VIDEOS, true)
        set(value) = prefs.edit().putBoolean(KEY_VIDEOS, value).commit().let {}

    /** PDFs in the chosen folders are indexed page by page; on unless the owner turns it off. */
    var includePdfs: Boolean
        get() = prefs.getBoolean(KEY_PDFS, true)
        set(value) = prefs.edit().putBoolean(KEY_PDFS, value).commit().let {}

    /** The most pages of one PDF that are indexed. */
    var pdfMaxPages: Int
        get() = prefs.getInt(KEY_PDF_MAX_PAGES, DEFAULT_PDF_MAX_PAGES).coerceIn(1, 2000)
        set(value) = prefs.edit().putInt(KEY_PDF_MAX_PAGES, value.coerceIn(1, 2000)).commit().let {}

    /** Sync, backup and restore may use mobile data; off = only Wi-Fi (or other unmetered networks). */
    var useMobileData: Boolean
        get() = prefs.getBoolean(KEY_MOBILE_DATA, false)
        set(value) = prefs.edit().putBoolean(KEY_MOBILE_DATA, value).commit().let {}

    /** The backend of this index as the platform holds it, kept here sealed so a run needs no call first. */
    var storage: StorageConfig?
        get() = prefs.getString(KEY_STORAGE, null)?.let { SecureStore.decrypt(it) }?.let { runCatching { StorageConfig.fromJson(JSONObject(it)) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_STORAGE) else putString(KEY_STORAGE, SecureStore.encrypt(value.toJson().toString()))
            }.commit()
        }

    /** False while the backend typed here has not reached the account yet (the next run sends it again). */
    var storageSynced: Boolean
        get() = prefs.getBoolean(KEY_STORAGE_SYNCED, true)
        set(value) = prefs.edit().putBoolean(KEY_STORAGE_SYNCED, value).commit().let {}

    /** When the account was last asked for the backend (a phone without one asks again after a day). */
    var storageAskedAt: Long
        get() = prefs.getLong(KEY_STORAGE_ASKED, 0L)
        set(value) = prefs.edit().putLong(KEY_STORAGE_ASKED, value).commit().let {}

    /** When the manifests in the backend were last read into the clone. */
    var manifestsReadAt: Long
        get() = prefs.getLong(KEY_MANIFESTS_READ, 0L)
        set(value) = prefs.edit().putLong(KEY_MANIFESTS_READ, value).commit().let {}

    var storageReport: StorageReport?
        get() = StorageReport.fromJson(prefs.getString(KEY_STORAGE_REPORT, null))
        set(value) = prefs.edit().putString(KEY_STORAGE_REPORT, value?.toJson()).commit().let {}

    /** The owner pressed Stop on the Storage screen: nothing starts on its own until they press a button there. */
    var storagePaused: Boolean
        get() = prefs.getBoolean(KEY_STORAGE_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_STORAGE_PAUSED, value).commit().let {}

    /** The owner asked for every original to come down from the backend (before a change or a clear). */
    var downloadAllOriginals: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOAD_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_DOWNLOAD_ALL, value).commit().let {}

    var pendingNotice: String?
        get() = prefs.getString(KEY_NOTICE, null)
        set(value) {
            prefs.edit().putString(KEY_NOTICE, value).apply()
        }

    fun savePendingSignIn(verifier: String, state: String) {
        prefs.edit()
            .putString(KEY_AUTH_VERIFIER, SecureStore.encrypt(verifier))
            .putString(KEY_AUTH_STATE, state)
            .putLong(KEY_AUTH_STARTED, System.currentTimeMillis())
            .commit()
    }

    fun takePendingSignIn(): Pair<String, String>? {
        val verifier = prefs.getString(KEY_AUTH_VERIFIER, null)?.let { SecureStore.decrypt(it) }
        val state = prefs.getString(KEY_AUTH_STATE, null)
        val started = prefs.getLong(KEY_AUTH_STARTED, 0L)
        prefs.edit().remove(KEY_AUTH_VERIFIER).remove(KEY_AUTH_STATE).remove(KEY_AUTH_STARTED).commit()
        if (verifier == null || state == null) return null
        if (System.currentTimeMillis() - started > SIGN_IN_TTL_MS) return null
        return verifier to state
    }

    fun signOut() {
        prefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_API_KEY)
            .remove(KEY_DEVICE_KEY)
            .remove(KEY_ACCOUNT)
            .remove(KEY_SOLR_URL)
            .remove(KEY_SOLR_USER)
            .remove(KEY_SOLR_PASSWORD)
            .remove(KEY_ENVIRONMENT)
            .remove(KEY_INDEX)
            .remove(KEY_REPORT)
            .remove(KEY_INDEX_CHECKED)
            .remove(KEY_STORAGE)
            .remove(KEY_STORAGE_REPORT)
            .remove(KEY_MANIFESTS_READ)
            .remove(KEY_STORAGE_ASKED)
            .commit()
    }

    companion object {
        private const val FILE = "opensolr_photos"
        private const val KEY_EMAIL = "email"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_INDEX = "index_name"
        private const val KEY_SOLR_URL = "solr_url"
        private const val KEY_SOLR_USER = "solr_user"
        private const val KEY_SOLR_PASSWORD = "solr_password"
        private const val KEY_ENVIRONMENT = "environment"
        private const val KEY_GROUP_BY = "group_by"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_FOLDERS_CHOSEN = "folders_chosen"
        private const val KEY_SCHEDULE = "schedule"
        private const val KEY_REPORT = "last_report"
        private const val KEY_REBUILD = "rebuild_approved"
        private const val KEY_RESYNC_IDS = "resync_ids"
        private const val KEY_COPY_ORIGINS = "copy_origins"
        private const val KEY_WORDING_RESET_IDS = "wording_reset_ids"
        private const val KEY_REREAD_ALL_SINCE = "reread_all_since"
        private const val KEY_WARNED = "warned_keys"
        private const val KEY_CHOSEN_INDEX = "chosen_index"
        private const val KEY_NOTICE = "notice"
        private const val KEY_MOBILE_DATA = "use_mobile_data"
        private const val KEY_VIDEOS = "include_videos"
        private const val KEY_PDFS = "include_pdfs"
        private const val KEY_PDF_MAX_PAGES = "pdf_max_pages"
        const val DEFAULT_PDF_MAX_PAGES = 100
        private const val KEY_STORAGE = "storage_backend"
        private const val KEY_STORAGE_SYNCED = "storage_synced"
        private const val KEY_STORAGE_ASKED = "storage_asked_at"
        private const val KEY_MANIFESTS_READ = "manifests_read_at"
        private const val KEY_STORAGE_REPORT = "storage_report"
        private const val KEY_STORAGE_PAUSED = "storage_paused"
        private const val KEY_DOWNLOAD_ALL = "download_all_originals"
        private const val KEY_UPDATE_CHECKED = "update_checked_at"
        private const val KEY_WATCH_SYNC = "watch_sync_at"
        private const val KEY_INDEX_CHECKED = "index_checked"
        private const val KEY_AUTO_PLACE_SINCE = "auto_place_on_since"
        private const val KEY_FOLDER_STAMP = "folder_stamp"
        private const val KEY_UPDATE_DISMISSED = "update_dismissed"
        private const val KEY_CACHE_SECONDS = "cache_seconds"
        private const val KEY_COLLAPSED = "collapsed_headings"
        private const val KEY_OPEN_FILTERS = "open_filter_sections"
        private const val KEY_CLONE_COMPLETE = "clone_complete"
        private const val KEY_CLONE_FORMAT = "clone_format"

        private const val KEY_FACETS = "browse_facets_v2"

        private const val CLONE_FORMAT = 2
        private const val KEY_FOLDED_ALBUMS = "folded_album_sections"
        private const val KEY_STATS_OPEN = "stats_open_sections"
        private const val KEY_ME_ZONES_OPEN = "me_zones_open"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_LEXICAL_WEIGHT = "lexical_weight"
        const val DEFAULT_LEXICAL_WEIGHT = 0.2f
        private const val KEY_MATCH_LEVEL = "match_level"
        const val DEFAULT_MATCH_LEVEL = 1
        private const val KEY_AUTH_VERIFIER = "auth_verifier"
        private const val KEY_AUTH_STATE = "auth_state"
        private const val KEY_AUTH_STARTED = "auth_started"
        private const val SIGN_IN_TTL_MS = 15 * 60 * 1000L
    }
}
