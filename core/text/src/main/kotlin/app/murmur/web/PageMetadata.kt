package app.murmur.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jsoup.nodes.Document as HtmlDocument
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Facts a page states about itself in meta tags and JSON-LD. */
data class PageMetadata(
    val title: String? = null,
    val headline: String? = null,
    val siteName: String? = null,
    val author: String? = null,
    val published: String? = null,
    val description: String? = null,
    val canonicalUrl: String? = null,
    val language: String? = null,
    /** JSON-LD `isAccessibleForFree: false`. */
    val paywalled: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun read(html: HtmlDocument): PageMetadata {
            fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
                html.selectFirst("meta[property=$k], meta[name=$k], meta[itemprop=$k]")?.attr("content")
                    ?.trim()?.takeIf { it.isNotEmpty() }
            }
            val ld = jsonLdArticle(html)
            val canonical = html.selectFirst("link[rel=canonical]")?.absUrl("href")?.takeIf { it.startsWith("http") }
                ?: meta("og:url")?.takeIf { it.startsWith("http") }
            val lang = html.selectFirst("html")?.attr("lang")?.takeIf { it.isNotBlank() }
                ?: meta("og:locale")
                ?: html.selectFirst("meta[http-equiv=content-language]")?.attr("content")
            return PageMetadata(
                title = meta("og:title", "twitter:title"),
                headline = ld?.string("headline"),
                siteName = meta("og:site_name", "application-name") ?: ld?.obj("publisher")?.string("name"),
                author = ld?.let { authorName(it["author"]) } ?: meta("author", "article:author", "parsely-author")
                    ?.takeIf { !it.startsWith("http") },
                published = meta("article:published_time", "datePublished", "parsely-pub-date", "pubdate")
                    ?: ld?.string("datePublished"),
                description = meta("og:description", "description", "twitter:description") ?: ld?.string("description"),
                canonicalUrl = canonical,
                language = lang?.let(::primaryLanguage),
                paywalled = ld?.let { isNotFree(it["isAccessibleForFree"]) } == true,
            )
        }

        /** "en-US" / "en_US" / "EN" -> "en". */
        fun primaryLanguage(tag: String): String? =
            tag.trim().split('-', '_', ',').firstOrNull()?.lowercase()?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }

        /** "2026-03-03T10:00:00Z" -> "March 3, 2026". */
        fun formatDate(iso: String?): String? {
            val day = iso?.trim()?.take(10) ?: return null
            return runCatching {
                LocalDate.parse(day).format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
            }.getOrNull()
        }

        private fun jsonLdArticle(html: HtmlDocument): JsonObject? {
            val candidates = html.select("script[type=application/ld+json]").flatMap { script ->
                val root = runCatching { json.parseToJsonElement(script.data()) }.getOrNull() ?: return@flatMap emptyList()
                flatten(root)
            }
            return candidates.firstOrNull { o -> types(o).any { "Article" in it || "Posting" in it || it == "Report" } }
                ?: candidates.firstOrNull { o -> types(o).any { it == "WebPage" } && o.containsKey("headline") }
        }

        private fun flatten(e: JsonElement): List<JsonObject> = when (e) {
            is JsonArray -> e.flatMap(::flatten)
            is JsonObject -> listOf(e) + (e["@graph"]?.let(::flatten) ?: emptyList())
            else -> emptyList()
        }

        private fun types(o: JsonObject): List<String> = when (val t = o["@type"]) {
            is JsonPrimitive -> listOfNotNull(t.contentOrNull)
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }

        private fun authorName(e: JsonElement?): String? = when (e) {
            is JsonPrimitive -> e.contentOrNull?.takeIf { !it.startsWith("http") }
            is JsonObject -> e.string("name")
            is JsonArray -> e.mapNotNull(::authorName).distinct().takeIf { it.isNotEmpty() }?.let(::joinNames)
            else -> null
        }

        private fun joinNames(names: List<String>): String = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
        }

        private fun isNotFree(e: JsonElement?): Boolean = when (e) {
            is JsonPrimitive -> e.booleanOrNull == false || e.contentOrNull.equals("false", ignoreCase = true)
            else -> false
        }

        private fun JsonObject.string(key: String): String? = when (val v = this[key]) {
            is JsonPrimitive -> v.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }

        private fun JsonObject.obj(key: String): JsonObject? = when (val v = this[key]) {
            is JsonObject -> v
            is JsonArray -> v.firstOrNull() as? JsonObject
            else -> null
        }
    }
}
