package com.opensolr.photos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.opensolr.photos.ui.AppRoot
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.Screen
import com.opensolr.photos.ui.theme.OpensolrPhotosTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.opensolr.photos.ui.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            OpensolrPhotosTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_AUTH_CALLBACK)?.let {
            intent.removeExtra(EXTRA_AUTH_CALLBACK)
            viewModel.completeSignIn(Uri.parse(it))
        }
        intent.getStringExtra(EXTRA_DESTINATION)?.let {
            intent.removeExtra(EXTRA_DESTINATION)
            if (it == DESTINATION_SYNC) viewModel.openIfSignedIn(Screen.Sync)
        }
    }

    companion object {
        const val EXTRA_AUTH_CALLBACK = "com.opensolr.photos.AUTH_CALLBACK"
        const val EXTRA_DESTINATION = "com.opensolr.photos.DESTINATION"
        const val DESTINATION_SYNC = "sync"
    }
}
