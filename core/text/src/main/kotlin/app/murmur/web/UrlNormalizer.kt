package app.murmur.web

import java.net.URLDecoder

/**
 * Cleans shared links: unwraps redirect wrappers (Google, Facebook, AMP viewers), removes
 * tracking parameters and fragments, and produces a [key] for spotting the same article twice.
 */
object UrlNormalizer {

    private val URL = Regex("""^(https?)://([^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$""", RegexOption.IGNORE_CASE)

    private val TRACKING_EXACT = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "igshid", "igsh",
        "mc_cid", "mc_eid", "ref", "ref_src", "ref_url", "referrer", "si", "_ga", "_gl", "mkt_tok",
        "vero_id", "vero_conv", "__s", "oly_anon_id", "oly_enc_id", "rb_clickid", "wt_mc", "smid",
        "smtyp", "cmpid", "ncid", "sr_share", "spm", "share_id", "utm", "s_cid", "ito", "cid_source",
    )
    private val TRACKING_PREFIX = listOf("utm_", "pk_", "mtm_", "hsa_", "_hs")
    private val KEY_HOST_PREFIXES = listOf("www.", "m.", "mobile.", "amp.")

    /** The address to fetch: unwrapped, without tracking parameters or a #fragment. */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (!url.contains("://")) url = "https://$url"
        repeat(3) { unwrap(url)?.let { url = it } ?: return@repeat }
        val m = URL.matchEntire(url) ?: return url
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2].lowercase().removeSuffix(":443").removeSuffix(":80")
        val path = m.groupValues[3].ifEmpty { "/" }
        val query = m.groupValues[4].removePrefix("?")
            .split('&')
            .filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
        return buildString {
            append(scheme).append("://").append(host).append(path)
            if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
        }
    }

    /** A scheme-less, host-normalized form used to detect duplicates. */
    fun key(raw: String): String {
        val url = normalize(raw)
        val m = URL.matchEntire(url) ?: return url.lowercase()
        var host = m.groupValues[2].lowercase()
        KEY_HOST_PREFIXES.forEach { if (host.startsWith(it) && host.count { c -> c == '.' } >= 2) host = host.removePrefix(it) }
        // Mobile hosts with a language or region first, such as en.m.wikipedia.org.
        host = host.replace(Regex("""^([a-z]{2,3}(?:-[a-z]+)?)\.(?:m|mobile)\.(?=[^.]+\.[^.]+$)"""), "$1.")
        var path = m.groupValues[3]
        path = path.removeSuffix("/amp").removeSuffix("/amp/").trimEnd('/')
        val query = m.groupValues[4].removePrefix("?").split('&').filter { it.isNotEmpty() }.sorted()
        return buildString {
            append(host).append(path)
            if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
        }
    }

    fun host(url: String): String? = URL.matchEntire(url.trim())?.groupValues?.get(2)?.lowercase()?.substringBefore(':')

    /** Host without "www." for display, e.g. "nytimes.com". */
    fun displayHost(url: String): String? = host(url)?.removePrefix("www.")?.removePrefix("m.")

    private fun isTracking(name: String): Boolean {
        val n = name.lowercase()
        return n in TRACKING_EXACT || TRACKING_PREFIX.any { n.startsWith(it) }
    }

    /** Returns the wrapped destination for known redirect/viewer URLs, or null. */
    private fun unwrap(url: String): String? {
        val m = URL.matchEntire(url) ?: return null
        val host = m.groupValues[2].lowercase()
        val path = m.groupValues[3]
        val params = parseQuery(m.groupValues[4])
        return when {
            host.matches(Regex("""(www\.)?google\.[a-z.]+""")) && path == "/url" -> params["q"] ?: params["url"]
            host.matches(Regex("""(www\.)?google\.[a-z.]+""")) && path.startsWith("/amp/s/") ->
                "https://" + path.removePrefix("/amp/s/")
            host.matches(Regex("""(www\.)?google\.[a-z.]+""")) && path.startsWith("/amp/") ->
                "http://" + path.removePrefix("/amp/")
            host.endsWith(".cdn.ampproject.org") && path.startsWith("/c/s/") -> "https://" + path.removePrefix("/c/s/")
            host.endsWith(".cdn.ampproject.org") && path.startsWith("/c/") -> "http://" + path.removePrefix("/c/")
            (host == "l.facebook.com" || host == "lm.facebook.com") && path == "/l.php" -> params["u"]
            host == "l.instagram.com" -> params["u"]
            host == "out.reddit.com" -> params["url"]
            host == "www.youtube.com" && path == "/redirect" -> params["q"]
            host == "slack-redir.net" -> params["url"]
            else -> null
        }?.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    private fun parseQuery(q: String): Map<String, String> = q.removePrefix("?").split('&')
        .filter { '=' in it }
        .associate { p ->
            val name = p.substringBefore('=')
            val value = runCatching { URLDecoder.decode(p.substringAfter('='), "UTF-8") }.getOrDefault(p.substringAfter('='))
            name to value
        }
}
