package app.murmur.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.murmur.text.Document
import app.murmur.text.EpubParser
import app.murmur.text.HtmlParagraphs
import app.murmur.text.PdfTextCleaner
import app.murmur.text.PlainText
import app.murmur.text.WebArticleExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.io.InputStream

data class Imported(val document: Document, val source: String, val kind: String)

class ImportException(message: String) : IOException(message)

/** Converts anything the user shares or opens into a [Document]. */
class Importer(private val context: Context, private val http: OkHttpClient) {

    @Volatile private var pdfBoxReady = false

    /** Shared or pasted text; a bare link is fetched and extracted as an article. */
    suspend fun fromText(text: String, subject: String? = null): Imported {
        PlainText.sharedUrl(text)?.let { url -> return fromUrl(url) }
        val doc = PlainText.toDocument(text, subject)
        if (doc.isEmpty) throw ImportException("Nothing to read")
        return Imported(doc, "Shared text", KIND_TEXT)
    }

    suspend fun fromHtml(html: String, subject: String?): Imported {
        val paragraphs = HtmlParagraphs.extract(Jsoup.parse(html).body())
        val doc = PlainText.toDocument(paragraphs.joinToString("\n\n"), subject)
        return Imported(doc, "Shared text", KIND_TEXT)
    }

    suspend fun fromUrl(url: String): Imported = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/pdf;q=0.9,*/*;q=0.8")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ImportException("Couldn't load page (HTTP ${response.code})")
            val body = response.body ?: throw ImportException("Empty page")
            val type = body.contentType()?.toString().orEmpty()
            val finalUrl = response.request.url.toString()
            when {
                "pdf" in type || finalUrl.endsWith(".pdf", ignoreCase = true) ->
                    Imported(readPdf(body.byteStream(), finalUrl.substringAfterLast('/')), finalUrl, KIND_PDF)
                else -> {
                    val doc = WebArticleExtractor.extract(finalUrl, body.string())
                    if (doc.isEmpty) throw ImportException("Couldn't find readable text on that page")
                    Imported(doc, finalUrl, KIND_WEB)
                }
            }
        }
    }

    suspend fun fromUri(uri: Uri, mimeHint: String? = null): Imported = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = mimeHint?.takeIf { it != "*/*" } ?: resolver.getType(uri).orEmpty()
        val name = displayName(uri) ?: uri.lastPathSegment ?: "Document"
        val lower = name.lowercase()
        fun open(): InputStream = resolver.openInputStream(uri) ?: throw ImportException("Can't open $name")
        when {
            mime == "application/pdf" || lower.endsWith(".pdf") ->
                Imported(open().use { readPdf(it, name) }, name, KIND_PDF)
            mime == "application/epub+zip" || lower.endsWith(".epub") -> {
                val tmp = File(context.cacheDir, "import.epub")
                open().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                try {
                    val doc = EpubParser.parse(tmp)
                    if (doc.isEmpty) throw ImportException("This book has no readable text (it may be DRM-protected)")
                    Imported(doc, name, KIND_EPUB)
                } finally {
                    tmp.delete()
                }
            }
            mime == "text/html" || lower.endsWith(".html") || lower.endsWith(".htm") ->
                fromHtml(open().use { it.readBytes().toString(Charsets.UTF_8) }, name.substringBeforeLast('.'))
            else -> {
                val text = open().use { it.readBytes().toString(Charsets.UTF_8) }
                val doc = PlainText.toDocument(text, name.substringBeforeLast('.'))
                if (doc.isEmpty) throw ImportException("Nothing to read in $name")
                Imported(doc, name, KIND_TEXT)
            }
        }
    }

    private fun readPdf(input: InputStream, name: String): Document {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(context)
            pdfBoxReady = true
        }
        PDDocument.load(input).use { pdf ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val pages = (1..pdf.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(pdf)
            }
            val title = pdf.documentInformation?.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
            val doc = PdfTextCleaner.clean(title, pages)
            if (doc.isEmpty) throw ImportException("This PDF has no text layer (it may be a scan)")
            return doc.copy(author = pdf.documentInformation?.author?.takeIf { it.isNotBlank() })
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_WEB = "web"
        const val KIND_PDF = "pdf"
        const val KIND_EPUB = "epub"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
