package com.opensolr.photos

import android.content.Context

object AppText {

    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context
    }

    fun s(id: Int, vararg args: Any): String = app?.getString(id, *args) ?: ""

    fun p(id: Int, count: Int, vararg args: Any): String = app?.resources?.getQuantityString(id, count, *args) ?: ""
}
