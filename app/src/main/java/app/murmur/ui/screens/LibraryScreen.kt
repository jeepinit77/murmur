package app.murmur.ui.screens

import android.content.ClipboardManager
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
import androidx.compose.material.icons.filled.Delete
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
import app.murmur.library.Imported
import app.murmur.library.Importer
import app.murmur.text.SentenceSegmenter
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

    fun doImport(block: suspend () -> Imported) {
        busy = true
        scope.launch {
            try {
                val imported = block()
                val count = SentenceSegmenter().segment(imported.document).size
                val meta = c.library.add(imported.document, imported.source, imported.kind, count)
                c.reader.open(meta.id, imported.document, startIndex = 0, autoplay = installed.isNotEmpty())
                onOpen(meta.id)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) doImport { c.importer.fromUri(uri) }
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
        },
        bottomBar = {
            if (state.docId != null) MiniPlayer(state, c.reader, onClick = onNowPlaying)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                            onClick = { onOpen(doc.id) },
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
            onConfirm = { text -> dialog = null; doImport { c.importer.fromText(text) } },
        )
        "url" -> TextInputDialog(
            title = "Web page link",
            initial = clipboardText(context)?.takeIf { it.startsWith("http") },
            singleLine = true,
            onDismiss = { dialog = null },
            onConfirm = { url ->
                dialog = null
                val fixed = if (url.startsWith("http")) url.trim() else "https://${url.trim()}"
                doImport { c.importer.fromUrl(fixed) }
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
private fun DocRow(doc: DocMeta, progress: Int, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                when (doc.kind) {
                    Importer.KIND_PDF -> Icons.Default.PictureAsPdf
                    Importer.KIND_EPUB -> Icons.Default.MenuBook
                    Importer.KIND_WEB -> Icons.Default.Article
                    else -> Icons.Default.ShortText
                },
                null,
            )
        },
        headlineContent = { Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(doc.author, sourceLabel(doc.source), DateUtils.getRelativeTimeSpanString(doc.addedAt).toString())
                        .joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
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
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.Delete, "Delete") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

private fun sourceLabel(source: String): String =
    if (source.startsWith("http")) source.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
    else source

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
