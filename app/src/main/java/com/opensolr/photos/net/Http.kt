package com.opensolr.photos.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one HTTP client of the app. HTTPS only (cleartext is disabled for the whole app in
 * res/xml/network_security_config.xml), system certificate authorities only, and no
 * redirects followed silently for API calls, so a credential is never re-sent to a host the
 * app did not name.
 */
object Http {

    /**
     * Shared client. The read timeout is long because the first picture after a restart of the
     * Opensolr AI service waits for the model to load.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()
}
