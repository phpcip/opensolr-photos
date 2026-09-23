package com.opensolr.photos.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.opensolr.photos.data.AppPrefs
import java.security.MessageDigest
import java.security.SecureRandom

object AuthFlow {

    const val CLIENT_ID = "opensolr-photos"
    const val REDIRECT_URI = "https://opensolr.com/app/callback"
    private const val AUTHORIZE_URL = "https://opensolr.com/app/authorize"

    private val random = SecureRandom()

    fun start(context: Context, prefs: AppPrefs) {
        val verifier = randomToken(48)
        val state = randomToken(24)
        prefs.savePendingSignIn(verifier, state)

        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challengeOf(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("device", deviceLabel())
            .build()

        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** This install as Account > Devices knows it: the phone's ANDROID_ID, prefixed so it stays unique across the apps. */
    fun deviceId(context: android.content.Context): String {
        @android.annotation.SuppressLint("HardwareIds")
        val raw = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty()
        return "photos-" + raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(40).padEnd(16, '0')
    }

    fun deviceLabel(): String {
        val raw = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
        else "${Build.MANUFACTURER} ${Build.MODEL}"
        return raw.filter { it.isLetterOrDigit() && it.code < 128 || it in " ._()-" }.trim().take(64)
    }
}
