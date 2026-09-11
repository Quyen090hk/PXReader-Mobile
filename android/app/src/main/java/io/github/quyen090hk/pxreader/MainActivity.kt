package io.github.quyen090hk.pxreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import io.github.quyen090hk.pxreader.ui.screens.warmUpReaderWebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    private val incomingUris = MutableStateFlow<List<Uri>>(emptyList())
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = AppContainer(applicationContext)
        acceptIncoming(intent)
        setContent {
            PxReaderApp(
                container = container,
                incoming = incomingUris,
                onIncomingConsumed = { incomingUris.value = emptyList() },
            )
        }
        // Pay Chromium's one-time startup cost while the user is looking at the library rather
        // than after they tap a book.
        window.decorView.postDelayed({ warmUpReaderWebView(applicationContext) }, 250)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIncoming(intent)
    }

    private fun acceptIncoming(intent: Intent?) {
        val accepted = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUri(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableUriList(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (accepted.isNotEmpty()) incomingUris.value = accepted
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelableUri(key: String): Uri? = getParcelableExtra(key)

@Suppress("DEPRECATION")
private fun Intent.parcelableUriList(key: String): List<Uri> =
    getParcelableArrayListExtra<Uri>(key).orEmpty()
