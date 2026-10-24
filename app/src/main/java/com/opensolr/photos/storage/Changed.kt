package com.opensolr.photos.storage

import android.content.Context

/**
 * Backup bookkeeping that must outlive a run cut short: the items whose file changed on this phone (they go up
 * again, and the web's small picture of the old file goes), and whether this phone's manifest still owes the rows
 * of files already sent.
 */
object Changed {

    private const val PREFS = "opensolr_photos_backup"
    private const val KEY_IDS = "changed_ids"
    private const val KEY_OWED = "manifest_owed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun mark(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty() + ids
        prefs(context).edit().putStringSet(KEY_IDS, now).apply()
    }

    fun has(context: Context, id: String): Boolean = prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty().contains(id)

    @Synchronized
    fun done(context: Context, id: String) {
        val now = prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty() - id
        prefs(context).edit().putStringSet(KEY_IDS, now).apply()
    }

    fun manifestOwed(context: Context): Boolean = prefs(context).getBoolean(KEY_OWED, false)

    fun manifestOwed(context: Context, owed: Boolean) {
        prefs(context).edit().putBoolean(KEY_OWED, owed).apply()
    }
}
