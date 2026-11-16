package app.murmur.web

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteIconLinksTest {
    private val page = "https://example.com/news/story"

    @Test fun prefersLargeIcons() {
        val html = """
            <head>
              <link rel="icon" href="/favicon-16.png" sizes="16x16">
              <link rel="apple-touch-icon" href="/touch.png">
              <link rel="icon" href="/favicon-192.png" sizes="192x192">
            </head>
        """
        assertEquals(
            listOf("https://example.com/touch.png", "https://example.com/favicon-192.png", "https://example.com/favicon-16.png"),
            SiteIconLinks.find(html, page),
        )
    }

    @Test fun skipsSvgAndMaskIcons() {
        val html = """
            <link rel="icon" href="/icon.svg" type="image/svg+xml">
            <link rel="mask-icon" href="/mask.png">
            <link rel="shortcut icon" href="favicon.ico">
        """
        assertEquals(listOf("https://example.com/news/favicon.ico"), SiteIconLinks.find(html, page))
    }

    @Test fun noIcons() {
        assertEquals(emptyList<String>(), SiteIconLinks.find("<p>Hi</p>", page))
    }
}

class UrlTitleTest {
    @Test fun recognizesAddresses() {
        org.junit.Assert.assertTrue(app.murmur.text.WebArticleExtractor.looksLikeUrl("nifty.org/nifty/gay/adult-friends/my-21st-birthday"))
        org.junit.Assert.assertTrue(app.murmur.text.WebArticleExtractor.looksLikeUrl("https://example.com/a"))
        org.junit.Assert.assertFalse(app.murmur.text.WebArticleExtractor.looksLikeUrl("Topographic isolation"))
        org.junit.Assert.assertFalse(app.murmur.text.WebArticleExtractor.looksLikeUrl("Node.js"))
    }

    @Test fun displayTitleUsesAddressWords() {
        assertEquals(
            "My 21st birthday",
            app.murmur.text.WebArticleExtractor.displayTitle("nifty.org/nifty/gay/adult-friends/my-21st-birthday", "https://www.nifty.org/nifty/gay/adult-friends/my-21st-birthday/"),
        )
        assertEquals("Topographic isolation", app.murmur.text.WebArticleExtractor.displayTitle("Topographic isolation", "https://en.wikipedia.org/wiki/Topographic_isolation"))
    }
}
