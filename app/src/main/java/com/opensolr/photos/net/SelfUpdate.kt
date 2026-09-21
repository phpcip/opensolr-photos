package com.opensolr.photos.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Downloads the latest release APK and installs it through the system installer. */
object SelfUpdate {

    sealed class Outcome {
        object Started : Outcome()
        object NeedsPermission : Outcome()
        object Invalid : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private const val MAX_BYTES = 80L * 1024 * 1024

    // GitHub answers the download with a redirect to its asset host; only those two hosts, over https, are followed.
    private val downloadClient by lazy {
        Http.client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                val host = url.host
                if (!url.isHttps || !(host == "github.com" || host.endsWith(".githubusercontent.com"))) throw IOException("unexpected update host")
                chain.proceed(chain.request())
            }
            .build()
    }
    private const val ACTION_RESULT = "com.opensolr.photos.SELF_UPDATE_RESULT"

    /** True when Google Play installed this copy: then Play does the updating, not the app. */
    fun fromPlay(context: Context): Boolean {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        } catch (e: Exception) {
            null
        }
        return installer == "com.android.vending"
    }

    fun openPlay(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun downloadAndInstall(context: Context, url: String, onProgress: (Int) -> Unit): Outcome = withContext(Dispatchers.IO) {
        if (!canInstall(context)) return@withContext Outcome.NeedsPermission
        val file = try {
            download(context, url, onProgress)
        } catch (e: Exception) {
            return@withContext Outcome.Failed(e.message ?: "download failed")
        }
        if (!isValidUpdate(context, file)) {
            file.delete()
            return@withContext Outcome.Invalid
        }
        try {
            install(context, file)
            Outcome.Started
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "install failed")
        }
    }

    private fun download(context: Context, url: String, onProgress: (Int) -> Unit): File {
        require(url.startsWith("https://github.com/phpcip/opensolr-photos/")) { "unexpected update address" }
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "opensolr-photos.apk")
        downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val body = response.body ?: throw IOException("GitHub answered with nothing")
            val total = body.contentLength()
            if (total > MAX_BYTES) throw IOException("update too large")
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > MAX_BYTES) throw IOException("update too large")
                        output.write(buffer, 0, n)
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun isValidUpdate(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (versionCode(archive) <= versionCode(installed)) return false
        val theirs = signers(archive)
        return theirs.isNotEmpty() && theirs == signers(installed)
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun signers(info: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") info.signatures
        } ?: return emptySet()
        val sha = MessageDigest.getInstance("SHA-256")
        return raw.map { sig -> sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("opensolr-photos.apk", 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, ResultReceiver::class.java).setAction(ACTION_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Shows the system's confirmation when Android asks for one; the new version replaces the app otherwise. */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
        }
    }
}
