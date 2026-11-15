package app.murmur.web

import app.murmur.library.ImportException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class PageFetcherTest {
    private lateinit var server: MockWebServer
    private val fetcher = PageFetcher(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun url(path: String = "/a") = server.url(path).toString()

    @Test fun usesMetaCharsetWhenTheHeaderHasNone() = runBlocking {
        val html = "<html><head><meta charset=\"windows-1252\"></head><body><p>café naïve</p></body></html>"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html")
                .setBody(Buffer().write(html.toByteArray(charset("windows-1252")))),
        )
        val page = fetcher.fetch(url())
        assertTrue(page.isHtml)
        assertTrue(page.text().contains("café naïve"))
    }

    @Test fun headerCharsetWins() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><meta charset=\"windows-1252\"></head><body>café</body></html>"),
        )
        assertTrue(fetcher.fetch(url()).text().contains("café"))
    }

    @Test fun refusesPagesOverTheSizeLimit() = runBlocking {
        val big = Buffer().write(ByteArray((PageFetcher.MAX_BYTES + 1024).toInt()) { 'a'.code.toByte() })
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(big))
        try {
            fetcher.fetch(url())
            fail("expected an error")
        } catch (e: ImportException) {
            assertEquals("This page is too large to import", e.message)
        }
    }

    @Test fun explainsHttpErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(503))
        val notFound = runCatching { fetcher.fetch(url()) }.exceptionOrNull() as ImportException
        assertFalse(notFound.retryable)
        assertEquals(404, notFound.httpStatus)
        val forbidden = runCatching { fetcher.fetch(url()) }.exceptionOrNull() as ImportException
        assertTrue(forbidden.needsSignIn)
        val down = runCatching { fetcher.fetch(url()) }.exceptionOrNull() as ImportException
        assertTrue(down.retryable)
    }

    @Test fun followsRedirectsToAPdf() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/paper.pdf"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-1.4"))
        val page = fetcher.fetch(url("/start"))
        assertTrue(page.isPdf)
        assertTrue(page.finalUrl.endsWith("/paper.pdf"))
    }

    @Test fun cancellationStopsTheRequest() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<p>slow</p>").setBodyDelay(3, TimeUnit.SECONDS))
        val start = System.currentTimeMillis()
        try {
            withTimeout(300) { fetcher.fetch(url()) }
            fail("expected a timeout")
        } catch (e: TimeoutCancellationException) {
            assertTrue(System.currentTimeMillis() - start < 2000)
        }
    }
}
