package com.opensolr.photos

import android.app.Application
import com.opensolr.photos.sync.Notifier
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Application entry point. Creates the notification channels once per process, before any
 * sync can post to them.
 */
class PhotosApp : Application() {

    /** The language picked on Me, for everything the app says outside a screen (notifications, sync). */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.opensolr.photos.ui.AppLanguage.wrap(base))
    }

    /**
     * Registers the sync-progress and account-alert channels.
     */
    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
        Notifier.createChannels(this)
        // Map tiles (OpenStreetMap) are cached in this app's private storage; the user agent
        // identifies the app to the tile servers, as their usage policy asks.
        Configuration.getInstance().apply {
            userAgentValue = "opensolr-photos/" + BuildConfig.VERSION_NAME
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
    }
}
