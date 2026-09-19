package com.opensolr.photos

import android.content.Context

/**
 * The app's words for code that runs outside a screen - the sync, the network layer, the errors it
 * raises (Cip, 2026-09-20): the application's own resources, which follow the language chosen on
 * Me (Android applies it on 13 and later, AppLanguage.wrap before that).
 */
object AppText {

    @Volatile private var app: Context? = null

    /** Called once by the application as it starts. */
    fun init(context: Context) {
        app = context
    }

    /** The string [id], filled with [args]. */
    fun s(id: Int, vararg args: Any): String = app?.getString(id, *args) ?: ""

    /** The plural [id] for [count], filled with [args]. */
    fun p(id: Int, count: Int, vararg args: Any): String = app?.resources?.getQuantityString(id, count, *args) ?: ""
}
