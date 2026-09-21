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

        Configuration.getInstance().apply {
            userAgentValue = "opensolr-photos/" + BuildConfig.VERSION_NAME
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
    }
}
