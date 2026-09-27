package com.opensolr.photos

import android.app.Application
import com.opensolr.photos.sync.Notifier
import org.osmdroid.config.Configuration
import java.io.File

class PhotosApp : Application() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.opensolr.photos.ui.AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
        Notifier.createChannels(this)
        // Every index request picks up a rotated password by itself; parallel 401s refresh once.
        com.opensolr.photos.net.SolrClient.credentials = { stale ->
            val prefs = com.opensolr.photos.data.AppPrefs(this)
            val current = prefs.connection
            if (current != null && current.indexName == stale.indexName && current.password != stale.password) current
            else if (current == null || current.indexName != stale.indexName) null
            else prefs.session?.let { com.opensolr.photos.index.IndexManager(this, prefs, com.opensolr.photos.net.OpensolrApi()).refreshConnection(it) }
        }

        Configuration.getInstance().apply {
            userAgentValue = "opensolr-photos/" + BuildConfig.VERSION_NAME
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
    }
}
