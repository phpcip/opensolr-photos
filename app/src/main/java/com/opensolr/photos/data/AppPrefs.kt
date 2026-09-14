package com.opensolr.photos.data

import android.content.Context
import org.json.JSONObject

/**
 * Everything the app remembers between runs, in one private SharedPreferences file.
 *
 * Secrets (the API key and the index password) go through [SecureStore] and are only ever
 * written encrypted. The file is excluded from cloud backup and device transfer
 * (res/xml/data_extraction_rules.xml), so a new phone always signs in again.
 */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The signed-in account, or null when there is none or the stored key cannot be decrypted.
     */
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
                    remove(KEY_API_KEY)
                } else {
                    putString(KEY_EMAIL, value.email)
                    putString(KEY_API_KEY, SecureStore.encrypt(value.apiKey))
                }
            }.commit()
        }

    /**
     * Where the phone's index answers, or null before the index was set up.
     */
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

    /**
     * Name of the index this phone has used before. Kept after the connection is dropped, so a
     * vanished index is reported as recreated rather than as a first setup.
     */
    val knownIndexName: String? get() = prefs.getString(KEY_INDEX, null)

    /**
     * Last known plan limits and usage.
     */
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

    /**
     * The folders (MediaStore relative paths such as "DCIM/Camera/") the user chose to index.
     */
    var folders: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_FOLDERS, value.toSet()).putBoolean(KEY_FOLDERS_CHOSEN, true).commit()
        }

    /**
     * True once the user confirmed a folder choice at least once.
     */
    val foldersChosen: Boolean get() = prefs.getBoolean(KEY_FOLDERS_CHOSEN, false)

    /**
     * How often the scheduled Re-Sync runs.
     */
    var schedule: SyncSchedule
        get() = runCatching { SyncSchedule.valueOf(prefs.getString(KEY_SCHEDULE, SyncSchedule.WEEKLY.name)!!) }
            .getOrDefault(SyncSchedule.WEEKLY)
        set(value) {
            prefs.edit().putString(KEY_SCHEDULE, value.name).apply()
        }

    /**
     * The result of the last sync run.
     */
    var lastReport: SyncReport?
        get() = SyncReport.fromJson(prefs.getString(KEY_REPORT, null))
        set(value) {
            prefs.edit().putString(KEY_REPORT, value?.toJson()).commit()
        }

    /**
     * True once the owner agreed to rebuild the index for a newer configuration. The next
     * sync copies the index into the cache, uploads the configuration, empties the index
     * and writes every photo again; then the flag is cleared.
     */
    var rebuildApproved: Boolean
        get() = prefs.getBoolean(KEY_REBUILD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REBUILD, value).commit()
        }

    /**
     * Photo ids the user asked to read again ("Re-sync selected"). The next sync reads them
     * with CLIP regardless of the cache, then clears the set.
     */
    var resyncIds: Set<String>
        get() = prefs.getStringSet(KEY_RESYNC_IDS, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_RESYNC_IDS, value.toSet()).commit()
        }

    /**
     * The index this phone uses, decided once: the one it created, or the one the owner
     * picked as "this device" on a phone that had none. Null until decided.
     */
    var chosenIndexName: String?
        get() = prefs.getString(KEY_CHOSEN_INDEX, null)
        set(value) {
            prefs.edit().putString(KEY_CHOSEN_INDEX, value).commit()
        }

    /**
     * Keys of the plan warnings already posted as notifications (see PlanWatch), so each is
     * posted once.
     */
    var warnedKeys: Set<String>
        get() = prefs.getStringSet(KEY_WARNED, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_WARNED, value.toSet()).commit()
        }

    /** When the latest GitHub release was last asked for, so it is asked at most once a day. */
    var updateCheckedAt: Long
        get() = prefs.getLong(KEY_UPDATE_CHECKED, 0L)
        set(value) {
            prefs.edit().putLong(KEY_UPDATE_CHECKED, value).apply()
        }

    /** The version whose update notice the user dismissed with "Not now"; shown again only for a newer one. */
    var updateDismissed: String?
        get() = prefs.getString(KEY_UPDATE_DISMISSED, null)
        set(value) {
            prefs.edit().putString(KEY_UPDATE_DISMISSED, value).apply()
        }

    /**
     * A message for the next screen the user sees (sign-in needed, sign-in cancelled...).
     */
    var pendingNotice: String?
        get() = prefs.getString(KEY_NOTICE, null)
        set(value) {
            prefs.edit().putString(KEY_NOTICE, value).apply()
        }

    /**
     * Stores the PKCE verifier and state of a sign-in that was just started in the browser.
     */
    fun savePendingSignIn(verifier: String, state: String) {
        prefs.edit()
            .putString(KEY_AUTH_VERIFIER, SecureStore.encrypt(verifier))
            .putString(KEY_AUTH_STATE, state)
            .putLong(KEY_AUTH_STARTED, System.currentTimeMillis())
            .commit()
    }

    /**
     * Returns and forgets the pending sign-in (verifier, state), or null when there is none or
     * it is older than [SIGN_IN_TTL_MS]. Forgetting it first makes every callback single-use.
     */
    fun takePendingSignIn(): Pair<String, String>? {
        val verifier = prefs.getString(KEY_AUTH_VERIFIER, null)?.let { SecureStore.decrypt(it) }
        val state = prefs.getString(KEY_AUTH_STATE, null)
        val started = prefs.getLong(KEY_AUTH_STARTED, 0L)
        prefs.edit().remove(KEY_AUTH_VERIFIER).remove(KEY_AUTH_STATE).remove(KEY_AUTH_STARTED).commit()
        if (verifier == null || state == null) return null
        if (System.currentTimeMillis() - started > SIGN_IN_TTL_MS) return null
        return verifier to state
    }

    /**
     * Forgets the account and the index connection. The folder choice and the schedule stay,
     * because they describe the phone, not the account.
     */
    fun signOut() {
        prefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_API_KEY)
            .remove(KEY_ACCOUNT)
            .remove(KEY_SOLR_URL)
            .remove(KEY_SOLR_USER)
            .remove(KEY_SOLR_PASSWORD)
            .remove(KEY_ENVIRONMENT)
            .remove(KEY_INDEX)
            .remove(KEY_REPORT)
            .commit()
    }

    companion object {
        private const val FILE = "opensolr_photos"
        private const val KEY_EMAIL = "email"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_INDEX = "index_name"
        private const val KEY_SOLR_URL = "solr_url"
        private const val KEY_SOLR_USER = "solr_user"
        private const val KEY_SOLR_PASSWORD = "solr_password"
        private const val KEY_ENVIRONMENT = "environment"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_FOLDERS_CHOSEN = "folders_chosen"
        private const val KEY_SCHEDULE = "schedule"
        private const val KEY_REPORT = "last_report"
        private const val KEY_REBUILD = "rebuild_approved"
        private const val KEY_RESYNC_IDS = "resync_ids"
        private const val KEY_WARNED = "warned_keys"
        private const val KEY_CHOSEN_INDEX = "chosen_index"
        private const val KEY_NOTICE = "notice"
        private const val KEY_UPDATE_CHECKED = "update_checked_at"
        private const val KEY_UPDATE_DISMISSED = "update_dismissed"
        private const val KEY_AUTH_VERIFIER = "auth_verifier"
        private const val KEY_AUTH_STATE = "auth_state"
        private const val KEY_AUTH_STARTED = "auth_started"
        private const val SIGN_IN_TTL_MS = 15 * 60 * 1000L
    }
}
