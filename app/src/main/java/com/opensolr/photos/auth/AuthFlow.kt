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

/**
 * Starts the Opensolr sign-in in the phone's browser.
 *
 * OAuth 2.0 authorization code flow with PKCE, the way RFC 8252 says native apps must do it:
 * the login page opens in a Custom Tab (the real browser, with its own cookies and password
 * manager), never in a WebView, so the app can never read what is typed there. The app keeps a
 * random verifier to itself and sends only its SHA-256 (the challenge); the one-time code that
 * comes back is worthless to anyone who does not also hold the verifier.
 */
object AuthFlow {

    const val CLIENT_ID = "opensolr-photos"
    const val REDIRECT_URI = "https://opensolr.com/app/callback"
    private const val AUTHORIZE_URL = "https://opensolr.com/app/authorize"

    private val random = SecureRandom()

    /**
     * Creates a fresh verifier and state, remembers them, and opens the authorize page.
     */
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

    /**
     * base64url(SHA-256(verifier)) without padding, the S256 challenge.
     */
    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * [bytes] random bytes as unpadded base64url.
     */
    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * "Manufacturer Model", limited to the characters and length the server accepts, shown on
     * the approval page so the person knows which phone is asking.
     */
    private fun deviceLabel(): String {
        val raw = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
        else "${Build.MANUFACTURER} ${Build.MODEL}"
        return raw.filter { it.isLetterOrDigit() && it.code < 128 || it in " ._()-" }.trim().take(64)
    }
}
