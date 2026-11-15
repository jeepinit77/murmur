package app.murmur.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.resume

class RenderedPage(val url: String, val html: String)

/**
 * Loads a page in an off-screen WebView so its JavaScript can build the article, then hands
 * back the resulting HTML. Used only when a plain download didn't contain readable text.
 */
class WebViewRenderer(private val context: Context) {

    private val mutex = Mutex()

    /** Returns null when no WebView is available on this phone. */
    suspend fun render(url: String, timeoutMs: Long = TIMEOUT_MS): RenderedPage? = mutex.withLock {
        withContext(Dispatchers.Main) {
            val view = runCatching { create() }.getOrNull() ?: return@withContext null
            try {
                var finished = false
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        finished = false
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        finished = true
                    }
                }
                view.loadUrl(url)
                val start = System.currentTimeMillis()
                var lastLength = -1
                var stableSince = 0L
                while (System.currentTimeMillis() - start < timeoutMs) {
                    delay(POLL_MS)
                    val length = view.eval("document.body ? document.body.innerText.length : 0")?.toIntOrNull() ?: 0
                    val now = System.currentTimeMillis()
                    if (length != lastLength) {
                        lastLength = length
                        stableSince = now
                    }
                    val settled = length > 0 && now - stableSince >= SETTLE_MS
                    if (settled && (finished || now - start > MAX_WAIT_FOR_LOAD_MS)) break
                }
                val html = view.eval("document.documentElement.outerHTML") ?: return@withContext null
                runCatching { CookieManager.getInstance().flush() }
                RenderedPage(view.url ?: url, html)
            } finally {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.userAgentString = PageFetcher.USER_AGENT
        // Lay it out at phone size so lazy-loading pages render their text.
        measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, WIDTH, HEIGHT)
    }

    /** Evaluates [script] and returns its result as a string. */
    private suspend fun WebView.eval(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { raw ->
            val value = runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull }.getOrNull()
            if (cont.isActive) cont.resume(value)
        }
    }

    companion object {
        const val TIMEOUT_MS = 20_000L
        private const val POLL_MS = 500L
        private const val SETTLE_MS = 1_500L
        private const val MAX_WAIT_FOR_LOAD_MS = 8_000L
        private const val WIDTH = 1080
        private const val HEIGHT = 2200
    }
}
