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
import com.tom_roush.pdfbox.io.MemoryUsageSetting
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
        try {
            importUri(uri, mimeHint)
        } catch (e: OutOfMemoryError) {
            throw ImportException("This file is too large to import")
        }
    }

    private suspend fun importUri(uri: Uri, mimeHint: String?): Imported {
        val resolver = context.contentResolver
        val mime = mimeHint?.takeIf { it != "*/*" } ?: resolver.getType(uri).orEmpty()
        val name = displayName(uri) ?: uri.lastPathSegment ?: "Document"
        val lower = name.lowercase()
        fun open(): InputStream = resolver.openInputStream(uri) ?: throw ImportException("Can't open $name")
        fun copyTo(file: File) = open().use { input -> file.outputStream().use { input.copyTo(it) } }
        return when {
            // Books and PDFs are parsed from a file of their own, not held in memory whole.
            mime == "application/pdf" || lower.endsWith(".pdf") ->
                Imported(withTempFile(".pdf", ::copyTo) { readPdf(it, name) }, name, KIND_PDF)
            mime == "application/epub+zip" || lower.endsWith(".epub") -> withTempFile(".epub", ::copyTo) { tmp ->
                val doc = withTables { mode, tables -> EpubParser.parse(tmp, mode, tables) }
                if (doc.isEmpty) throw ImportException("This book has no readable text (it may be DRM-protected)")
                Imported(doc, name, KIND_EPUB)
            }
            mime == "text/html" || lower.endsWith(".html") || lower.endsWith(".htm") ->
                fromHtml(open().use { readCapped(it, name) }.toString(Charsets.UTF_8), name.substringBeforeLast('.'))
            else -> {
                val text = open().use { readCapped(it, name) }.toString(Charsets.UTF_8)
                val doc = withTables { mode, tables -> PlainText.toDocument(text, name.substringBeforeLast('.'), mode, tables) }
                if (doc.isEmpty) throw ImportException("Nothing to read in $name")
                Imported(doc, name, KIND_TEXT)
            }
        }
    }

    /** Reads a downloaded PDF; used for links that point at PDF files. */
    suspend fun readPdf(bytes: ByteArray, name: String): Document = withContext(Dispatchers.IO) {
        try {
            withTempFile(".pdf", { it.writeBytes(bytes) }) { readPdf(it, name) }
        } catch (e: OutOfMemoryError) {
            throw ImportException("This PDF is too large to import")
        }
    }

    private fun readPdf(file: File, name: String): Document {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(context)
            pdfBoxReady = true
        }
        // Temp files rather than memory for the parsed PDF, so large PDFs don't run out of memory.
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
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

    /** A file of its own in the cache, deleted afterward; concurrent imports never share one. */
    private inline fun <T> withTempFile(suffix: String, fill: (File) -> Unit, use: (File) -> T): T {
        val file = File.createTempFile("import-", suffix, context.cacheDir)
        try {
            fill(file)
            return use(file)
        } finally {
            file.delete()
        }
    }

    /** Reads a text or HTML file, refusing ones too large to hold as text. */
    private fun readCapped(input: InputStream, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_TEXT_BYTES) throw ImportException("$name is too large to import (over ${MAX_TEXT_BYTES / 1_000_000} MB)")
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        /** Larger text files are refused rather than risking running out of memory. */
        private const val MAX_TEXT_BYTES = 20_000_000
        const val KIND_TEXT = "text"
        const val KIND_WEB = "web"
        const val KIND_PDF = "pdf"
        const val KIND_EPUB = "epub"
    }
}
