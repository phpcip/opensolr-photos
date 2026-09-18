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

/**
 * The single activity. Hosts the Compose UI and receives the two kinds of intents that steer it:
 * the end of a browser sign-in (forwarded by AuthCallbackActivity) and taps on notifications
 * that should land on a given screen.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /**
     * Sets up edge-to-edge drawing and the UI, then handles the launching intent.
     */
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

    /**
     * Back in front, from the gallery or anywhere else: the view model checks whether the
     * chosen folders changed meanwhile and syncs only if they did.
     */
    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    /**
     * Handles intents delivered while the activity is already open.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * Routes a sign-in callback or a notification destination to the view model, once.
     */
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
