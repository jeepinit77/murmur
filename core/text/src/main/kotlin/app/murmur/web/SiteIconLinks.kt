package app.murmur.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Finds a site's icon addresses in a page, best first, for the library's site icons. */
object SiteIconLinks {

    /** Icon links declared in [html], best first. Relative links are resolved against [pageUrl]. */
    fun find(html: String, pageUrl: String): List<String> = find(Jsoup.parse(html, pageUrl))

    fun find(doc: Document): List<String> =
        doc.select("link[rel][href]")
            .mapNotNull { link ->
                val rel = link.attr("rel").lowercase().split(' ', '\t').toSet()
                val rank = when {
                    "apple-touch-icon" in rel || "apple-touch-icon-precomposed" in rel -> 0
                    "icon" in rel && "mask-icon" !in rel -> 1
                    else -> return@mapNotNull null
                }
                val href = link.absUrl("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                // SVG icons can't be decoded by BitmapFactory.
                if (href.substringBefore('?').endsWith(".svg", true) || link.attr("type").contains("svg")) return@mapNotNull null
                val size = link.attr("sizes").lowercase().substringBefore('x').toIntOrNull() ?: if (rank == 0) 180 else 32
                Triple(href, rank, size)
            }
            // Prefer icons near 96px or larger; very large ones are fine but slower to download.
            .sortedWith(compareBy({ if (it.third >= 64) 0 else 1 }, { it.second }, { -minOf(it.third, 256) }))
            .map { it.first }
            .distinct()

    /** Standard locations to try when a page declares no icon. */
    fun defaults(host: String): List<String> = listOf("https://$host/apple-touch-icon.png", "https://$host/favicon.ico")
}
