package app.murmur.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Site icons for library rows, downloaded once per site and kept in app storage. Icons are
 * keyed by host without "www." or "m.", so every article from a site shares one.
 */
class SiteIcons(context: Context, base: OkHttpClient, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "site-icons").apply { mkdirs() }
    /** When each host was last tried without finding an icon, so failures aren't retried every time. */
    private val attempts = context.getSharedPreferences("site_icons", Context.MODE_PRIVATE)
    private val http = base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val cache = LruCache<String, ImageBitmap>(64)

    private val _updates = MutableStateFlow(0)
    /** Changes whenever a new icon is saved, so rows showing a fallback can look again. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    fun key(url: String): String? = if (url.startsWith("http")) UrlNormalizer.displayHost(url) else null

    private fun file(key: String) = File(dir, "$key.png")

    /** The icon if it's already in memory, without touching storage. */
    fun cached(url: String): ImageBitmap? = key(url)?.let { cache.get(it) }

    /** The saved icon for [url]'s site, or null if there isn't one (yet). */
    suspend fun load(url: String): ImageBitmap? {
        val key = key(url) ?: return null
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(key)
            if (!f.exists()) return@withContext null
            BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { cache.put(key, it) }
        }
    }

    /**
     * Downloads [url]'s site icon in the background if it isn't saved yet. [candidates] are icon
     * links found in the page; the site's standard locations are tried after them.
     */
    fun fetch(url: String, candidates: List<String> = emptyList()) {
        val key = key(url) ?: return
        val host = UrlNormalizer.host(url) ?: return
        if (file(key).exists()) return
        val last = attempts.getLong(key, 0L)
        if (candidates.isEmpty() && System.currentTimeMillis() - last < RETRY_AFTER_MS) return
        if (!inFlight.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                val best = (candidates + SiteIconLinks.defaults(host)).distinct().firstNotNullOfOrNull(::download)
                if (best != null) {
                    file(key).outputStream().use { best.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    cache.put(key, best.asImageBitmap())
                    attempts.edit { remove(key) }
                    _updates.value++
                } else {
                    attempts.edit { putLong(key, System.currentTimeMillis()) }
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** Deletes the icon for [url]'s site, when nothing from that site is left in the library. */
    fun remove(url: String) {
        val key = key(url) ?: return
        cache.remove(key)
        scope.launch(Dispatchers.IO) { file(key).delete() }
    }

    /** A decoded, downscaled icon from [iconUrl], or null if it's missing, too big, or too small. */
    private fun download(iconUrl: String): Bitmap? = runCatching {
        http.newCall(Request.Builder().url(iconUrl).header("Accept", "image/*").header("User-Agent", PageFetcher.USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val source = body.source()
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) return null
            val bytes = source.buffer.readByteArray()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (bitmap.width < MIN_SIZE || bitmap.height < MIN_SIZE) return null
            if (bitmap.width <= SIZE && bitmap.height <= SIZE) bitmap
            else Bitmap.createScaledBitmap(bitmap, SIZE, SIZE * bitmap.height / bitmap.width, true)
        }
    }.onFailure { Log.d(TAG, "No icon at $iconUrl: ${it.message}") }.getOrNull()

    companion object {
        private const val TAG = "MurmurIcons"
        private const val MAX_BYTES = 256L * 1024
        private const val SIZE = 96
        private const val MIN_SIZE = 32
        private const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
