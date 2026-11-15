package app.murmur.ui.screens

import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.layout.Box
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import app.murmur.library.DocMeta
import app.murmur.R
import app.murmur.library.DocStatus
import app.murmur.library.Importer
import app.murmur.library.Imports
import app.murmur.library.PendingImportException
import app.murmur.text.ListeningTime
import app.murmur.web.SharedLink
import app.murmur.web.SiteSignInActivity
import app.murmur.web.UrlNormalizer
import android.content.Intent
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (String) -> Unit,
    onVoices: () -> Unit,
    onSettings: () -> Unit,
    onNowPlaying: () -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val docs by c.library.docs.collectAsStateWithLifecycle()
    val progress by c.library.progress.collectAsStateWithLifecycle()
    val state by c.reader.state.collectAsStateWithLifecycle()
    val installed by c.voices.installed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "text" | "url"
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<String?>(null) }
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    var clipboardLink by remember { mutableStateOf<SharedLink?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Offer a link the user just copied, unless it's already in the library or was dismissed.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(400) // clipboard reads need window focus
            val link = clipboardLinkText(context)?.let { SharedLink.parse(it) }
            val key = link?.let { UrlNormalizer.key(it.url) }
            clipboardLink = link?.takeIf { key != c.settings.dismissedClipboardKey && c.library.findByUrlKey(key!!) == null }
        }
    }

    fun doImport(announceSaved: Boolean = true, block: suspend (onStage: (String) -> Unit) -> Imports.Outcome) {
        busy = true
        scope.launch {
            try {
                val outcome = block { stage = it }
                if (outcome.existing && announceSaved) Toast.makeText(context, R.string.resuming_saved, Toast.LENGTH_SHORT).show()
                c.reader.open(
                    outcome.meta.id, outcome.document,
                    startIndex = if (outcome.existing) null else 0, autoplay = installed.isNotEmpty(),
                )
                c.reader.state.value.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                onOpen(outcome.meta.id)
            } catch (e: PendingImportException) {
                Toast.makeText(context, "${e.message}. ${context.getString(R.string.saved_for_later)}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                stage = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) doImport { c.imports.save(c.importer.fromUri(uri)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Murmur") },
                actions = {
                    IconButton(onClick = onVoices) { Icon(Icons.Default.RecordVoiceOver, "Voices") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            // One sized child: Scaffold drops zero-size children of this slot, which hid the menu.
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Paste text") }, leadingIcon = { Icon(Icons.Default.ShortText, null) },
                        onClick = { addMenu = false; dialog = "text" },
                    )
                    DropdownMenuItem(
                        text = { Text("Web page link") }, leadingIcon = { Icon(Icons.Default.Link, null) },
                        onClick = { addMenu = false; dialog = "url" },
                    )
                    DropdownMenuItem(
                        text = { Text("PDF, EPUB or text file") }, leadingIcon = { Icon(Icons.Default.Description, null) },
                        onClick = {
                            addMenu = false
                            picker.launch(arrayOf("application/pdf", "application/epub+zip", "text/plain", "text/html"))
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (state.docId != null) MiniPlayer(state, c.reader, onClick = onNowPlaying)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                stage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            clipboardLink?.let { link ->
                ClipboardLinkCard(
                    link = link,
                    onAdd = {
                        clipboardLink = null
                        doImport { onStage -> c.imports.link(link.url, link.titleHint, onStage) }
                    },
                    onDismiss = {
                        c.settings.dismissedClipboardKey = UrlNormalizer.key(link.url)
                        clipboardLink = null
                    },
                )
            }
            if (installed.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onVoices)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Download a voice to get started", style = MaterialTheme.typography.titleMedium)
                        Text("Voices run entirely on your phone. Nothing you read leaves the device.")
                    }
                }
            }
            if (docs.isEmpty()) {
                EmptyLibrary()
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(docs, key = { it.id }) { doc ->
                        DocRow(
                            doc = doc,
                            progress = progress[doc.id] ?: 0,
                            speed = speed,
                            onClick = {
                                if (doc.status == DocStatus.READY) {
                                    onOpen(doc.id)
                                } else {
                                    scope.launch { c.imports.retry(doc.id) }
                                    Toast.makeText(context, "Trying again", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onReload = {
                                doImport(announceSaved = false) { onStage ->
                                    val (outcome, index) = c.imports.reload(doc.id, onStage)
                                    c.reader.replaceDocument(doc.id, outcome.document, index)
                                    Imports.Outcome(outcome.meta, outcome.document, existing = true)
                                }
                            },
                            onOpenOriginal = { openInBrowser(context, doc.source) },
                            onSignIn = { context.startActivity(SiteSignInActivity.intent(context, doc.source, doc.id)) },
                            onDelete = {
                                if (state.docId == doc.id) c.reader.close()
                                c.library.delete(doc.id)
                            },
                        )
                    }
                }
            }
        }
    }

    when (dialog) {
        "text" -> TextInputDialog(
            title = "Paste text",
            initial = clipboardText(context),
            singleLine = false,
            onDismiss = { dialog = null },
            onConfirm = { text -> dialog = null; doImport { onStage -> c.imports.text(text, onStage = onStage) } },
        )
        "url" -> TextInputDialog(
            title = "Web page link",
            initial = clipboardText(context)?.takeIf { it.startsWith("http") },
            singleLine = true,
            onDismiss = { dialog = null },
            onConfirm = { url ->
                dialog = null
                val fixed = if (url.trim().startsWith("http")) url.trim() else "https://${url.trim()}"
                doImport { onStage -> c.imports.link(fixed, onStage = onStage) }
            },
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
        Text("Share any article, text selection, PDF or EPUB to Murmur and it starts reading immediately. You can also tap Add.")
        Text("Tip: select text in any app and choose “Read aloud”.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DocRow(
    doc: DocMeta,
    progress: Int,
    speed: Float,
    onClick: () -> Unit,
    onReload: () -> Unit,
    onOpenOriginal: () -> Unit,
    onSignIn: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                when {
                    doc.status == DocStatus.PENDING -> Icons.Default.CloudOff
                    doc.status == DocStatus.FAILED -> Icons.Default.ErrorOutline
                    doc.kind == Importer.KIND_PDF -> Icons.Default.PictureAsPdf
                    doc.kind == Importer.KIND_EPUB -> Icons.Default.MenuBook
                    doc.kind == Importer.KIND_WEB -> Icons.Default.Article
                    else -> Icons.Default.ShortText
                },
                null,
            )
        },
        headlineContent = { Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                when (doc.status) {
                    DocStatus.PENDING -> Text(
                        listOfNotNull(sourceLabel(doc.source), "Waiting for network").joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    DocStatus.FAILED -> Text(
                        (doc.error ?: "Couldn't import") + " · Tap to try again",
                        color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    DocStatus.READY -> Text(
                        listOfNotNull(
                            doc.author,
                            sourceLabel(doc.source),
                            doc.wordCount.takeIf { it > 0 }?.let { ListeningTime.label(it, speed) },
                            DateUtils.getRelativeTimeSpanString(doc.addedAt).toString(),
                        ).joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (doc.incomplete && doc.status == DocStatus.READY) {
                    Text(
                        "May be incomplete", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (doc.segmentCount > 0 && progress > 0) {
                    LinearProgressIndicator(
                        progress = { progress.toFloat() / doc.segmentCount },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (doc.isWeb && doc.status == DocStatus.READY) {
                        DropdownMenuItem(text = { Text("Reload") }, onClick = { menu = false; onReload() })
                    }
                    if (doc.source.startsWith("http")) {
                        DropdownMenuItem(text = { Text("Open original") }, onClick = { menu = false; onOpenOriginal() })
                        DropdownMenuItem(text = { Text("Sign in to site") }, onClick = { menu = false; onSignIn() })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

@Composable
private fun ClipboardLinkCard(link: SharedLink, onAdd: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ContentPaste, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Add link from clipboard", style = MaterialTheme.typography.titleSmall)
                Text(
                    link.titleHint ?: UrlNormalizer.displayHost(link.url) ?: link.url,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onAdd) { Text("Add") }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

private fun sourceLabel(source: String): String =
    if (source.startsWith("http")) source.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
    else source

private var lastClipTimestamp = 0L

/**
 * The clipboard text when it probably holds a new link. Only the clip's description is checked
 * first, which doesn't trigger Android's "pasted from your clipboard" message, so the clip is
 * read only when it's new and (on Android 12+) classified as a link.
 */
private fun clipboardLinkText(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val desc = cm.primaryClipDescription ?: return null
    if (!desc.hasMimeType("text/*")) return null
    if (desc.timestamp == lastClipTimestamp) return null
    lastClipTimestamp = desc.timestamp
    if (Build.VERSION.SDK_INT >= 31) {
        if (desc.classificationStatus != ClipDescription.CLASSIFICATION_COMPLETE) return null
        if (desc.getConfidenceScore(TextClassifier.TYPE_URL) < 0.5f) return null
    }
    clipboardText(context)
}.getOrNull()

private fun clipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

@Composable
fun TextInputDialog(
    title: String,
    initial: String?,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) 0.dp else 160.dp, max = 360.dp),
            )
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) { Text("Read") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
