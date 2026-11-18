package app.murmur.web

import android.webkit.CookieManager
import app.murmur.library.ImportException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A downloaded page or file. */
class FetchedPage(
    val finalUrl: String,
    val contentType: String,
    val bytes: ByteArray,
    /** Charset from the Content-Type header, if any. */
    private val headerCharset: String?,
    /** Content-Language header, if any. */
    val headerLanguage: String?,
) {
    val isPdf: Boolean get() = "pdf" in contentType || (contentType.isBlank() && finalUrl.substringBefore('?').endsWith(".pdf", true))
    val isHtml: Boolean get() = contentType.isBlank() || "html" in contentType || "xml" in contentType
    val isPlainText: Boolean get() = contentType.startsWith("text/plain")

    /** Decodes the page, trusting a byte-order mark or `<meta charset>` over a generic header default. */
    fun text(): String {
        val header = headerCharset?.takeIf { !it.equals("iso-8859-1", ignoreCase = true) }
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        if (header != null) return String(bytes, header)
        if (!isHtml) return String(bytes, Charsets.UTF_8)
        // Jsoup reads the BOM and <meta charset> and falls back to UTF-8.
        return Jsoup.parse(ByteArrayInputStream(bytes), null, finalUrl).outerHtml()
    }
}

/**
 * Downloads pages for import: cancellable, size-limited, https first, with friendly errors.
 * Sends the cookies of sites the user signed in to through [SiteSignInActivity].
 */
class PageFetcher(base: OkHttpClient) {

    private val http: OkHttpClient = base.newBuilder().cookieJar(WebViewCookieJar()).build()

    suspend fun fetch(url: String, accept: String = ACCEPT_HTML): FetchedPage {
        val https = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else null
        if (https != null) {
            try {
                return fetchOnce(https, accept)
            } catch (e: ImportException) {
                if (e.httpStatus != null) throw e
            } catch (e: IOException) {
                // No https version; fall back to the plain address below.
            }
        }
        return fetchOnce(url, accept)
    }

    private suspend fun fetchOnce(url: String, accept: String): FetchedPage {
        val request = try {
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "${Locale.getDefault().toLanguageTag()},en;q=0.8")
                .build()
        } catch (e: IllegalArgumentException) {
            throw ImportException("That isn't a valid web address")
        }
        return try {
            http.newCall(request).await { response -> read(response) }
        } catch (e: ImportException) {
            throw e
        } catch (e: UnknownHostException) {
            throw ImportException("No internet connection, or the site doesn't exist", retryable = true)
        } catch (e: SocketTimeoutException) {
            throw ImportException("The site took too long to respond", retryable = true)
        } catch (e: SSLException) {
            throw ImportException("Couldn't make a secure connection to the site")
        } catch (e: IOException) {
            if (e.message?.contains("Canceled", ignoreCase = true) == true) throw e
            throw ImportException("Couldn't load the page: ${e.message ?: "network error"}", retryable = true)
        }
    }

    private fun read(response: Response): FetchedPage {
        val code = response.code
        if (!response.isSuccessful) {
            throw when (code) {
                401, 403 -> ImportException("This page requires sign-in", needsSignIn = true, httpStatus = code)
                404, 410 -> ImportException("Page not found (HTTP $code)", httpStatus = code)
                429 -> ImportException("The site is limiting requests. Try again later.", retryable = true, httpStatus = code)
                in 500..599 -> ImportException("The site isn't responding (HTTP $code)", retryable = true, httpStatus = code)
                else -> ImportException("Couldn't load the page (HTTP $code)", httpStatus = code)
            }
        }
        val body = response.body ?: throw ImportException("The page is empty")
        val type = body.contentType()
        val source = body.source()
        val buffer = Buffer()
        // PDFs (papers, reports) are often far larger than web pages, and are parsed from a file.
        val limit = if (type?.subtype == "pdf") MAX_PDF_BYTES else MAX_BYTES
        while (source.read(buffer, 64 * 1024) != -1L) {
            if (buffer.size > limit) throw ImportException(if (limit == MAX_PDF_BYTES) "This PDF is too large to import" else "This page is too large to import")
        }
        return FetchedPage(
            finalUrl = response.request.url.toString(),
            contentType = type?.let { "${it.type}/${it.subtype}" }.orEmpty().lowercase(),
            bytes = buffer.readByteArray(),
            headerCharset = type?.charset()?.name(),
            headerLanguage = response.header("Content-Language"),
        )
    }

    companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        const val MAX_PDF_BYTES = 100L * 1024 * 1024
        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/pdf;q=0.9,*/*;q=0.8"
        const val ACCEPT_JSON = "application/json,text/html;q=0.5"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}

/** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the request. */
private suspend fun <T> Call.await(block: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(block) }
            if (cont.isActive) result.fold(cont::resume, cont::resumeWithException)
        }
    })
}

/** Shares cookies with WebView, so pages the user signed in to in Murmur load as signed in. */
private class WebViewCookieJar : CookieJar {
    private val manager: CookieManager? by lazy { runCatching { CookieManager.getInstance() }.getOrNull() }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = manager?.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = manager ?: return
        cookies.forEach { m.setCookie(url.toString(), it.toString()) }
    }
}
