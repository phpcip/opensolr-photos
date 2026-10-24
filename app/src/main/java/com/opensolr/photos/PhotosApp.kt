package com.opensolr.photos

import android.app.Application
import com.opensolr.photos.sync.Notifier
import org.osmdroid.config.Configuration
import java.io.File

class PhotosApp : Application(), coil.ImageLoaderFactory {

    override fun newImageLoader(): coil.ImageLoader = com.opensolr.photos.ui.Images.loader(this)

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

        // the photos only the backend has, for the grid; the daily storage run
        com.opensolr.photos.storage.CloudFiles.load(this)
        // which files are on this phone, looked up off the main thread
        Thread {
            // what an earlier run left behind goes before anything else reads the app's folders
            // a photo whose overwrite the app died in gets its own bytes back (only here: no edit runs yet)
            runCatching { com.opensolr.photos.media.PhotoEditor.restoreUnfinished(this) }
            runCatching { com.opensolr.photos.storage.Residue.sweep(this) }
            runCatching { com.opensolr.photos.storage.ClipThumbs.warm(this); com.opensolr.photos.ui.Thumbs.warm(this) }
            runCatching { com.opensolr.photos.storage.LocalFiles.refresh(this) }
            // what was deleted on the other devices goes from here as the app opens
            runCatching { kotlinx.coroutines.runBlocking { com.opensolr.photos.storage.Deletions.push(this@PhotosApp); com.opensolr.photos.storage.Deletions.pull(this@PhotosApp) } }
        }.start()
        com.opensolr.photos.storage.StorageScheduler.applySchedule(this)
        // a backup or restore cut short (reinstall, killed app) carries on at once, never after a wait
        if (com.opensolr.photos.data.AppPrefs(this).storage != null) com.opensolr.photos.storage.StorageScheduler.runNow(this)

        Configuration.getInstance().apply {
            userAgentValue = "opensolr-photos/" + BuildConfig.VERSION_NAME
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
    }
}
