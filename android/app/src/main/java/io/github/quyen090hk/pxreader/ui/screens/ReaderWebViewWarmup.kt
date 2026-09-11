package io.github.quyen090hk.pxreader.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebView

private var warmupStarted = false

/** Initializes the shared Chromium engine before the first reader screen is requested. */
@SuppressLint("SetJavaScriptEnabled")
fun warmUpReaderWebView(context: Context) {
    if (warmupStarted) return
    warmupStarted = true
    runCatching {
        WebView(context.applicationContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            loadData(
                "<html><head><meta name=\"color-scheme\" content=\"dark light\"></head><body></body></html>",
                "text/html",
                "utf-8",
            )
            postDelayed({
                stopLoading()
                destroy()
            }, WARMUP_LIFETIME_MS)
        }
    }.onFailure {
        warmupStarted = false
    }
}

private const val WARMUP_LIFETIME_MS = 1_500L
