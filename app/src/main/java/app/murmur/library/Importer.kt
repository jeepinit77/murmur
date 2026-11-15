package app.murmur.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.murmur.text.Document
import app.murmur.text.EpubParser
import app.murmur.settings.Settings
import app.murmur.text.HtmlText
import app.murmur.text.PdfTextCleaner
import app.murmur.text.PlainText
import app.murmur.web.TableAi
import app.murmur.web.TableMode
import app.murmur.web.TableSummaries
import app.murmur.web.TableSummarizer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.io.InputStream

data class Imported(val document: Document, val source: String, val kind: String)

class ImportException(
    message: String,
    /** Worth trying again later: no network, a timeout, or a temporary server error. */
    val retryable: Boolean = false,
    /** The site wants the user to sign in (HTTP 401/403). */
    val needsSignIn: Boolean = false,
    val httpStatus: Int? = null,
) : IOException(message)

/**
 * Converts anything the user shares or opens into a [Document]. Tables follow the Tables setting
 * in every format that keeps them (pasted Markdown, HTML, EPUB); [tableAi] summarizes them.
 */
class Importer(
    private val context: Context,
    private val settings: Settings,
    private val tableAi: TableAi? = null,
) {

    @Volatile private var pdfBoxReady = false

    /** Shared or pasted text. Links are handled by [Imports.link] before this is called. */
    suspend fun fromText(text: String, subject: String? = null): Imported {
        val doc = withTables { mode, tables -> PlainText.toDocument(text, subject, mode, tables) }
        if (doc.isEmpty) throw ImportException("Nothing to read")
        return Imported(doc, "Shared text", KIND_TEXT)
    }

    suspend fun fromHtml(html: String, subject: String?): Imported {
        val doc = withTables { mode, tables -> HtmlText.toDocument(Jsoup.parse(html).body(), subject, mode, tables) }
        return Imported(doc, "Shared text", KIND_TEXT)
    }

    /** Runs [parse] off the main thread with the Tables setting, summarizing tables when that's chosen and possible. */
    private suspend fun withTables(parse: (TableMode, TableSummarizer) -> Document): Document {
        val options = settings.extractOptions()
        return TableSummaries.extract(options, tableAi, defer = { doc, later -> doc.copy(pendingTables = later) }) { tables ->
            withContext(Dispatchers.Default) { parse(options.tables, tables) }
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
                    val doc = withTables { mode, tables -> EpubParser.parse(tmp, mode, tables) }
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
                val doc = withTables { mode, tables -> PlainText.toDocument(text, name.substringBeforeLast('.'), mode, tables) }
                if (doc.isEmpty) throw ImportException("Nothing to read in $name")
                Imported(doc, name, KIND_TEXT)
            }
        }
    }

    /** Reads a downloaded PDF; used for links that point at PDF files. */
    suspend fun readPdf(bytes: ByteArray, name: String): Document = withContext(Dispatchers.IO) {
        readPdf(bytes.inputStream(), name)
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
    }
}
