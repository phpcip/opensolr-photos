package com.opensolr.photos

import android.app.Application
import com.opensolr.photos.sync.Notifier

/**
 * Application entry point. Creates the notification channels once per process, before any
 * sync can post to them.
 */
class PhotosApp : Application() {

    /**
     * Registers the sync-progress and account-alert channels.
     */
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
