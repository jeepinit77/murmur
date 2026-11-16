package app.murmur.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import app.murmur.R
import app.murmur.container
import app.murmur.playback.PlaybackService
import app.murmur.playback.ShareAction
import app.murmur.playback.SpeakRequest
import app.murmur.library.DocMeta
import app.murmur.settings.SavedPageMode
import app.murmur.settings.ShareMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import app.murmur.ui.MainActivity

/**
 * The T2S-style "share → it just starts talking" path.
 *
 * This activity never draws anything: it turns the incoming intent into a [SpeakRequest],
 * starts [PlaybackService] as a foreground service (allowed because we are momentarily the
 * foreground app), and finishes in the same frame, leaving the user in the app they shared
 * from. The media notification then provides pause/skip controls.
 */
class ShareToSpeakActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun handle(intent: Intent) {
        // "Add to queue" and "Save for later" are aliases of this activity in the share menu.
        val action = when (intent.component?.className) {
            ShareTargets.ADD_TO_QUEUE -> ShareAction.QUEUE
            ShareTargets.SAVE_FOR_LATER -> ShareAction.SAVE
            else -> ShareAction.READ
        }
        val request = parse(intent)?.copy(action = action)
        if (request == null) {
            Toast.makeText(this, R.string.nothing_to_read, Toast.LENGTH_SHORT).show()
            return
        }
        val c = container
        if (action == ShareAction.SAVE) {
            // Saving needs no voice and never asks about a saved copy.
            start(this, request)
            return
        }
        if (!c.voices.hasAnyInstalled()) {
            // First run: there is nothing to speak with yet, so open the voice picker.
            Toast.makeText(this, R.string.need_voice, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_VOICES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        savedCopyToAskAbout(request)?.let { saved ->
            startActivity(SavedPageActivity.intent(this, request, saved.title))
            return
        }
        start(this, request)
    }

    /**
     * A saved copy of a shared link, when the user wants to be asked whether to resume it.
     * The library index is in memory once loaded; the timeout only matters on a cold start.
     */
    private fun savedCopyToAskAbout(request: SpeakRequest): DocMeta? {
        val c = container
        if (c.settings.savedPageMode.value != SavedPageMode.ASK) return null
        val url = request.linkUrl() ?: return null
        return runBlocking { withTimeoutOrNull(1_000) { c.imports.savedCopy(url) } }
    }

    companion object {
        /** Hands [request] to the playback service and says whether it's reading or queued. */
        fun start(context: Context, request: SpeakRequest) {
            val c = context.container
            PlaybackService.speak(context, request)
            // The service says when a saved item is ready.
            if (request.action == ShareAction.SAVE) return
            val queueWhenBusy = request.action == ShareAction.QUEUE || c.settings.shareMode.value == ShareMode.QUEUE
            val queued = queueWhenBusy && c.reader.isBusy()
            Toast.makeText(context, if (queued) R.string.queued else R.string.reading, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parse(intent: Intent): SpeakRequest? {
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                    ?.takeIf { it.isNotBlank() }?.let { SpeakRequest(text = it) }

            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                when {
                    stream != null -> SpeakRequest(uri = stream, mime = intent.type, subject = subject)
                    !text.isNullOrBlank() -> SpeakRequest(text = text, subject = subject)
                    !html.isNullOrBlank() -> SpeakRequest(html = html, subject = subject)
                    else -> null
                }
            }

            // "Open with" on a web link.
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }
                ?.let { SpeakRequest(text = it.toString(), subject = subject) }

            Intent.ACTION_SEND_MULTIPLE -> {
                val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }
                val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                when {
                    !texts.isNullOrEmpty() -> SpeakRequest(text = texts.joinToString("\n\n"), subject = subject)
                    !streams.isNullOrEmpty() -> SpeakRequest(uri = streams.first(), mime = intent.type, subject = subject)
                    else -> null
                }
            }

            else -> null
        }
    }
}
