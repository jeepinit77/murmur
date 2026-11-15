package app.murmur.web

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Table summaries from Gemini Nano through the ML Kit GenAI Prompt API (beta). Runs on phones
 * with AICore and Gemini Nano, such as recent Pixel and Galaxy phones; elsewhere [status] is
 * [TableAiStatus.UNSUPPORTED] and Settings says so. Logs under the "MurmurTables" tag.
 */
class GeminiNanoTableAi(private val scope: CoroutineScope) : TableAi {

    private val model: GenerativeModel by lazy { Generation.getClient() }
    /** Gemini Nano runs one request at a time; queue them instead of failing with BUSY. */
    private val lock = Mutex()
    @Volatile private var available = false
    @Volatile private var download: Job? = null

    override suspend fun status(): TableAiStatus {
        val status = try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> TableAiStatus.READY
                FeatureStatus.DOWNLOADABLE ->
                    if (download?.isActive == true) TableAiStatus.DOWNLOADING else TableAiStatus.NEEDS_DOWNLOAD
                FeatureStatus.DOWNLOADING -> TableAiStatus.DOWNLOADING
                else -> TableAiStatus.UNSUPPORTED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano status check failed", e)
            TableAiStatus.ERROR
        }
        Log.i(TAG, "Gemini Nano status: $status")
        if (status == TableAiStatus.READY) available = true
        return status
    }

    override fun prepare() {
        scope.launch {
            if (status() == TableAiStatus.NEEDS_DOWNLOAD) startDownload()
        }
    }

    override suspend fun summarize(table: String): String? {
        if (!available) {
            when (status()) {
                TableAiStatus.READY -> Unit
                TableAiStatus.NEEDS_DOWNLOAD, TableAiStatus.DOWNLOADING -> {
                    startDownload()
                    Log.i(TAG, "Table summary postponed: Gemini Nano is downloading")
                    throw TableAiUnavailableNow("Gemini Nano is downloading")
                }
                else -> {
                    Log.i(TAG, "Table read row by row: Gemini Nano isn't available")
                    return null
                }
            }
        }
        val rows = table.lineSequence().count()
        val start = SystemClock.elapsedRealtime()
        return try {
            val text = lock.withLock {
                val request = generateContentRequest(TextPart(PROMPT + table)) {
                    temperature = 0.2f
                    topK = 16
                    maxOutputTokens = 256
                }
                model.generateContent(request).candidates.firstOrNull()?.text
            }
            Log.i(TAG, "Summarized a $rows-line table in ${SystemClock.elapsedRealtime() - start} ms" + if (text.isNullOrBlank()) " (empty answer)" else "")
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<GenAiException>().firstOrNull()?.errorCode
            if (code in TRY_LATER) {
                // AICore only runs for the app on screen; summarize when Murmur is opened.
                Log.i(TAG, "Table summary postponed (error $code): ${e.message}")
                throw TableAiUnavailableNow(e.message ?: "Gemini Nano can't run now", e)
            }
            Log.w(TAG, "Table summary failed after ${SystemClock.elapsedRealtime() - start} ms; reading row by row", e)
            null
        }
    }

    private fun startDownload() {
        if (download?.isActive == true) return
        download = scope.launch(Dispatchers.IO) {
            try {
                model.download().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadStarted -> Log.i(TAG, "Gemini Nano download started")
                        is DownloadStatus.DownloadCompleted -> {
                            available = true
                            Log.i(TAG, "Gemini Nano download finished")
                        }
                        is DownloadStatus.DownloadFailed -> Log.w(TAG, "Gemini Nano download failed", s.e)
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano download failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "MurmurTables"
        /** Errors that clear up later: app not on screen, model busy, battery quota. */
        val TRY_LATER = setOf(
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            GenAiException.ErrorCode.BUSY,
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
        )
        const val PROMPT =
            "Summarize this table for someone who is listening to it, not reading it. " +
                "Write 2 to 4 short sentences in plain language. Say what the table compares, " +
                "point out the highest and lowest values and any clear pattern, and don't list every row. " +
                "Use only facts from the table, and don't guess what kind of things the rows describe. Write plain text with no markdown, lists or headings. " +
                "Cells are separated by \" | \" and the first row is usually the header.\n\nTable:\n"
    }
}
