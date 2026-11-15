package app.murmur.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import app.murmur.container
import app.murmur.ui.MurmurTheme
import kotlinx.coroutines.launch

/**
 * An in-app browser for signing in to a site. Its cookies are shared with Murmur's page
 * downloads and renderer, so articles from sites the user subscribes to import in full.
 * Murmur never sees the password; the site's own sign-in page handles it.
 */
class SiteSignInActivity : ComponentActivity() {

    private var webView: WebView? = null

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val reloadId = intent.getStringExtra(EXTRA_RELOAD_ID)

        setContent {
            MurmurTheme {
                var host by remember { mutableStateOf(UrlNormalizer.displayHost(url).orEmpty()) }
                var loading by remember { mutableStateOf(true) }
                BackHandler {
                    val v = webView
                    if (v != null && v.canGoBack()) v.goBack() else done(reloadId)
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Close") } },
                            title = {
                                Column {
                                    Text("Sign in to site", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        host, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            actions = {
                                TextButton(onClick = { done(reloadId) }) { Text("Done") }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                                            loading = true
                                            u?.let { host = UrlNormalizer.displayHost(it).orEmpty() }
                                        }

                                        override fun onPageFinished(view: WebView, u: String?) {
                                            loading = false
                                        }
                                    }
                                    loadUrl(url)
                                    webView = this
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun done(reloadId: String?) {
        CookieManager.getInstance().flush()
        if (reloadId != null) {
            val c = container
            Toast.makeText(this, "Reloading article", Toast.LENGTH_SHORT).show()
            c.appScope.launch {
                try {
                    val (outcome, index) = c.imports.reload(reloadId)
                    c.reader.replaceDocument(reloadId, outcome.document, index)
                } catch (e: Exception) {
                    Toast.makeText(applicationContext, e.message ?: "Couldn't reload the article", Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_RELOAD_ID = "reload_id"

        /** Opens [url] for signing in; when [reloadId] is set, that article is reloaded after Done. */
        fun intent(context: Context, url: String, reloadId: String? = null): Intent =
            Intent(context, SiteSignInActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .apply { reloadId?.let { putExtra(EXTRA_RELOAD_ID, it) } }

        /** Removes every website sign-in made in Murmur. */
        fun clearSignIns() {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }
}
