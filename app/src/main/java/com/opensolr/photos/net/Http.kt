package com.opensolr.photos.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .eventListenerFactory { call -> Timing(call.request().url.encodedPath.takeLast(40)) }
        .build()

    /** Debug: phase timings of every HTTP call, logcat tag OsTiming (09/22/2026). */
    private class Timing(private val path: String) : okhttp3.EventListener() {
        private val t0 = System.nanoTime()
        private val marks = StringBuilder()
        private fun mark(name: String) { marks.append(name).append('=').append((System.nanoTime() - t0) / 1_000_000).append(' ') }
        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) = mark("dns")
        override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) = mark("connect")
        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) = mark("tls")
        override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) = mark("conn")
        override fun requestBodyEnd(call: okhttp3.Call, byteCount: Long) = mark("sent")
        override fun responseHeadersStart(call: okhttp3.Call) = mark("firstByte")
        override fun callEnd(call: okhttp3.Call) { mark("end"); android.util.Log.i("OsTiming", "http $path $marks") }
        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { mark("FAILED"); android.util.Log.i("OsTiming", "http $path $marks $ioe") }
    }
}
