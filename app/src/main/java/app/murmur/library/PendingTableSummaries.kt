package app.murmur.library

import android.util.Log
import app.murmur.playback.Reader
import app.murmur.settings.Settings
import app.murmur.text.SentenceSegmenter
import app.murmur.web.PositionMapper
import app.murmur.web.TableAi
import app.murmur.web.TableMode
import app.murmur.web.TableSummaries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Summarizes tables that were read row by row at import time because the on-device model
 * couldn't run then. Gemini Nano only runs for the app on screen, so items shared from another
 * app are summarized the next time Murmur is opened ([run] from the activity's onStart).
 */
class PendingTableSummaries(
    private val library: LibraryStore,
    private val settings: Settings,
    private val tableAi: TableAi?,
    private val reader: Reader,
    private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)
    /** Asked to run while already running; go through the library once more afterward. */
    private val again = AtomicBoolean(false)

    /** Call while Murmur is on screen. Safe to call often: runs one pass at a time. */
    fun run() {
        val ai = tableAi ?: return
        if (settings.tableMode.value != TableMode.SUMMARIZE) return
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        scope.launch {
            try {
                do {
                    again.set(false)
                    for (meta in library.all().filter { it.pendingTables && it.status == DocStatus.READY }) {
                        if (!complete(meta, ai)) return@launch
                    }
                } while (again.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't summarize postponed tables", e)
            } finally {
                running.set(false)
            }
        }
    }

    /** Returns false when the model still can't run, so the rest can wait too. */
    private suspend fun complete(meta: DocMeta, ai: TableAi): Boolean {
        val old = library.load(meta.id) ?: return true
        val doc = TableSummaries.completePending(old, ai, settings.labelAiSummaries.value) ?: return false
        val segmenter = SentenceSegmenter()
        val (segments, index) = withContext(Dispatchers.Default) {
            val segments = segmenter.segment(doc)
            val anchor = PositionMapper.anchor(old, segmenter.segment(old), library.progressOf(meta.id))
            segments to (anchor?.let { PositionMapper.map(it, doc, segments) } ?: 0)
        }
        library.replace(meta.id, doc, meta.source, meta.kind, segments.size, emptyList()) ?: return true
        library.setProgress(meta.id, index)
        reader.replaceDocument(meta.id, doc, index)
        Log.i(TAG, "Summarized postponed tables in \"${meta.title}\"; ${doc.pendingTables.size} still waiting")
        return doc.pendingTables.isEmpty()
    }

    private companion object {
        const val TAG = "MurmurTables"
    }
}
