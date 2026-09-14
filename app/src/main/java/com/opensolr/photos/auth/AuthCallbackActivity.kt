package com.opensolr.photos.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.opensolr.photos.MainActivity

/**
 * Receives the end of the browser sign-in: the verified App Link
 * https://opensolr.com/app/callback, or opensolr-photos://auth when the browser fell back to the
 * button on that page.
 *
 * It does nothing itself. It hands the callback URI to [MainActivity], which checks the state
 * against the one it saved and swaps the code for the account credentials, then closes.
 */
class AuthCallbackActivity : Activity() {

    /**
     * Forwards the callback URI and finishes immediately.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val forward = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (data != null) putExtra(MainActivity.EXTRA_AUTH_CALLBACK, data.toString())
        }
        startActivity(forward)
        finish()
    }
}
