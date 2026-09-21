package com.opensolr.photos.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.opensolr.photos.MainActivity

class AuthCallbackActivity : Activity() {

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
