package app.murmur.ui.screens

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import app.murmur.library.LibraryStore
import app.murmur.library.SearchHit
import app.murmur.text.Snippet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.murmur.text.WebArticleExtractor
import app.murmur.ui.library.DocIcon
import app.murmur.ui.library.Equalizer
import app.murmur.ui.library.AppLogo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (String) -> Unit,
    /** Opens a document with its find bar showing the query. */
    onOpenFind: (String, String) -> Unit,
    onVoices: () -> Unit,
    onSettings: () -> Unit,
    onNowPlaying: () -> Unit,
    onNotificationReading: () -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val docs by c.library.docs.collectAsStateWithLifecycle()
    // Deleted items disappear at once but are only removed after the Undo message has gone.
    val snackbar = remember { SnackbarHostState() }
    var pendingDeletes by remember { mutableStateOf(emptySet<String>()) }
    val progress by c.library.progress.collectAsStateWithLifecycle()
    val state by c.reader.state.collectAsStateWithLifecycle()
    val playingContent by c.reader.content.collectAsStateWithLifecycle()
    val rewindSeconds by c.settings.rewindSeconds.collectAsStateWithLifecycle()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    val installed by c.voices.installed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "text" | "url"
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<String?>(null) }
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    var clipboardLink by remember { mutableStateOf<SharedLink?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var fullText by rememberSaveable { mutableStateOf(true) }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    val notificationReading by c.settings.notificationReading.collectAsStateWithLifecycle()
    val finished by c.library.finished.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var notificationTip by remember { mutableStateOf(!c.settings.notificationTipDismissed) }

    LaunchedEffect(searching, query, fullText, docs) {
        hits = null
        if (!searching || query.isBlank()) return@LaunchedEffect
        delay(250) // wait for typing to pause
        hits = c.library.search(query, fullText)
    }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

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
                c.cues.playError()
                Toast.makeText(context, "${e.message}. ${context.getString(R.string.saved_for_later)}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                c.cues.playError()
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (searching) {
                SearchTopBar(
                    query = query,
                    placeholder = "Search library",
                    onQueryChange = { query = it },
                    onClose = { searching = false; query = "" },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppLogo(32.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Murmur", style = MaterialTheme.typography.headlineSmall)
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (docs.isNotEmpty()) IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search") }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (searching) return@Scaffold // no Add button while searching
            // One sized child: Scaffold drops zero-size children of this slot, which hid the menu.
            Box {
                // The label helps while the library is empty; afterwards the + is enough.
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Default.Add, if (docs.isEmpty()) null else "Add") },
                    text = { Text("Add") },
                    expanded = docs.isEmpty(),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
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
            if (state.docId != null) {
                val meta = docs.firstOrNull { it.id == state.docId }
                NowPlayingBar(
                    state = state,
                    content = playingContent,
                    reader = c.reader,
                    rewindSeconds = rewindSeconds,
                    expanded = playerExpanded,
                    onExpandedChange = { playerExpanded = it },
                    onVoices = onVoices,
                    onOpen = onNowPlaying,
                    source = meta?.source,
                    kind = meta?.kind,
                )
            }
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
            // Suggest notification reading once the library is in use; never shown again after Dismiss.
            if (notificationTip && !notificationReading && !searching && installed.isNotEmpty() && docs.isNotEmpty()) {
                NotificationReadingCard(
                    onSetUp = onNotificationReading,
                    onDismiss = {
                        c.settings.notificationTipDismissed = true
                        notificationTip = false
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
            if (searching) {
                SearchResults(
                    hits = hits,
                    query = query,
                    fullText = fullText,
                    onFullTextChange = { fullText = it },
                    progress = progress,
                    finished = finished,
                    speed = speed,
                    onOpen = { hit ->
                        when {
                            hit.meta.status != DocStatus.READY -> Unit
                            hit.snippet != null -> onOpenFind(hit.meta.id, query.trim())
                            else -> onOpen(hit.meta.id)
                        }
                    },
                )
            } else if (docs.isEmpty()) {
                EmptyLibrary()
            } else {
                FilterChips(filter, onChange = { filter = it })
                val shown = docs.filter { it.id !in pendingDeletes && filter.matches(it, progress[it.id] ?: 0, it.id in finished) }
                if (shown.isEmpty()) {
                    Text(
                        filter.emptyText, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
                LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)) {
                    itemsIndexed(shown, key = { _, doc -> doc.id }) { index, doc ->
                        val nowPlaying = state.docId == doc.id
                        // Inset dividers between rows, left out next to the highlighted row.
                        if (index > 0 && !nowPlaying && shown[index - 1].id != state.docId) {
                            HorizontalDivider(
                                Modifier.padding(start = 84.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            )
                        }
                        DocRow(
                            doc = doc,
                            progress = progress[doc.id] ?: 0,
                            speed = speed,
                            finished = doc.id in finished,
                            nowPlaying = nowPlaying,
                            playing = nowPlaying && state.playWhenReady && !state.ended,
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
                            onSetFinished = { done -> c.library.setFinished(doc.id, done) },
                            onDelete = {
                                if (state.docId == doc.id) c.reader.close()
                                pendingDeletes = pendingDeletes + doc.id
                                // The removal waits for the Undo message's outcome. It runs in the app's scope,
                                // so leaving the library doesn't cancel it; without an answer it goes ahead.
                                val undone = CompletableDeferred<Boolean>()
                                c.appScope.launch {
                                    if (withTimeoutOrNull(UNDO_WINDOW_MS) { undone.await() } == true) return@launch
                                    c.reader.removeFromQueue(doc.id)
                                    c.library.delete(doc.id)
                                    val site = c.siteIcons.key(doc.source)
                                    if (site != null && c.library.docs.value.none { it.id != doc.id && c.siteIcons.key(it.source) == site }) {
                                        c.siteIcons.remove(doc.source)
                                    }
                                }
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        "Deleted “${doc.title}”",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Long,
                                    )
                                    val undo = result == SnackbarResult.ActionPerformed
                                    undone.complete(undo)
                                    if (undo) pendingDeletes = pendingDeletes - doc.id
                                }
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
            icon = Icons.Default.ShortText,
            initial = clipboardText(context),
            singleLine = false,
            onDismiss = { dialog = null },
            onConfirm = { text -> dialog = null; doImport { onStage -> c.imports.text(text, onStage = onStage) } },
        )
        "url" -> TextInputDialog(
            title = "Web page link",
            icon = Icons.Default.Link,
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
private fun SearchResults(
    hits: List<SearchHit>?,
    query: String,
    fullText: Boolean,
    onFullTextChange: (Boolean) -> Unit,
    progress: Map<String, Int>,
    finished: Set<String>,
    speed: Float,
    onOpen: (SearchHit) -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = fullText, onClick = { onFullTextChange(true) }, label = { Text("Full text") })
        FilterChip(selected = !fullText, onClick = { onFullTextChange(false) }, label = { Text("Titles only") })
    }
    when {
        query.isBlank() -> Unit
        hits == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        hits.isEmpty() -> Text(
            "No results", style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(hits, key = { it.meta.id }) { hit ->
                DocRow(
                    doc = hit.meta,
                    progress = progress[hit.meta.id] ?: 0,
                    speed = speed,
                    finished = hit.meta.id in finished,
                    showDate = true,
                    onClick = { onOpen(hit) },
                    snippet = hit.snippet,
                    matchCount = hit.count,
                )
            }
        }
    }
}

/** A top app bar holding a search field, with a back button that closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(placeholder, maxLines = 1) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(); keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            actions()
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear") }
        },
    )
}

@Composable
private fun FilterChips(selected: LibraryFilter, onChange: (LibraryFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(LibraryFilter.entries) { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onChange(f) },
                label = { Text(f.label) },
                leadingIcon = { Icon(f.icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
                shape = CircleShape,
            )
        }
    }
}

@Composable
private fun SnippetText(snippet: Snippet, matchCount: Int) {
    val mark = MaterialTheme.colorScheme.tertiaryContainer
    val onMark = MaterialTheme.colorScheme.onTertiaryContainer
    val text = remember(snippet, mark) {
        buildAnnotatedString {
            append(snippet.text)
            addStyle(SpanStyle(background = mark, color = onMark, fontWeight = FontWeight.Medium), snippet.start, snippet.end)
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (matchCount > 1) {
            val max = LibraryStore.MAX_COUNTED_MATCHES
            Text(
                if (matchCount >= max) "$max+ matches" else "$matchCount matches",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
            )
        }
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

/** How the library list is narrowed down by the chips under the top bar. */
private enum class LibraryFilter(val label: String) {
    ALL("All"), READING("Reading"), UNREAD("Unread"), DONE("Done");

    val icon: ImageVector
        get() = when (this) {
            ALL -> Icons.AutoMirrored.Filled.List
            READING -> Icons.Default.PlayCircleOutline
            UNREAD -> Icons.Default.RadioButtonUnchecked
            DONE -> Icons.Default.Check
        }

    fun matches(doc: DocMeta, progress: Int, finished: Boolean): Boolean = when (this) {
        ALL -> true
        DONE -> finished
        READING -> !finished && progress > 0
        UNREAD -> !finished && progress <= 0
    }

    val emptyText: String
        get() = when (this) {
            ALL -> "Nothing here yet"
            READING -> "Nothing in progress"
            UNREAD -> "Nothing unread"
            DONE -> "Nothing marked as done"
        }
}

@Composable
private fun DocRow(
    doc: DocMeta,
    progress: Int,
    speed: Float,
    onClick: () -> Unit,
    finished: Boolean = false,
    /** The item loaded in the player; [playing] says whether it's being read right now. */
    nowPlaying: Boolean = false,
    playing: Boolean = false,
    showDate: Boolean = false,
    onReload: () -> Unit = {},
    onOpenOriginal: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onSetFinished: (Boolean) -> Unit = {},
    /** Null hides the More options menu, as in search results. */
    onDelete: (() -> Unit)? = null,
    snippet: Snippet? = null,
    matchCount: Int = 0,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(if (nowPlaying) colors.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocIcon(doc.source, doc.kind, doc.status)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(
                WebArticleExtractor.displayTitle(doc.title, doc.source),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            when (doc.status) {
                DocStatus.PENDING -> Text(
                    listOfNotNull(sourceLabel(doc.source), "Waiting for network").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                DocStatus.FAILED -> Text(
                    (doc.error ?: "Couldn't import") + " · Tap to try again",
                    style = MaterialTheme.typography.bodyMedium, color = colors.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                DocStatus.READY -> Text(
                    listOfNotNull(
                        doc.author,
                        sourceLabel(doc.source),
                        when {
                            finished -> "Done"
                            doc.wordCount > 0 -> ListeningTime.remainingLabel(doc.wordCount, progress, doc.segmentCount, speed)
                            else -> null
                        },
                        if (showDate) DateUtils.getRelativeTimeSpanString(doc.addedAt).toString() else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            snippet?.let { SnippetText(it, matchCount) }
            if (doc.incomplete && doc.status == DocStatus.READY) {
                Text(
                    "May be incomplete", style = MaterialTheme.typography.labelMedium,
                    color = colors.tertiary, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (doc.status == DocStatus.READY && doc.segmentCount > 0) {
                val fraction = if (finished) 1f else (progress.toFloat() / doc.segmentCount).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        if (nowPlaying) {
            Equalizer(
                playing = playing,
                modifier = Modifier.padding(horizontal = 4.dp)
                    .semantics { contentDescription = if (playing) "Playing" else "Paused" },
            )
        }
        if (onDelete != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (doc.status == DocStatus.READY) {
                        if (finished) {
                            DropdownMenuItem(text = { Text("Mark as unread") }, onClick = { menu = false; onSetFinished(false) })
                        } else {
                            DropdownMenuItem(text = { Text("Mark as done") }, onClick = { menu = false; onSetFinished(true) })
                        }
                    }
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
        }
    }
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

@Composable
private fun NotificationReadingCard(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Read notifications aloud", style = MaterialTheme.typography.titleSmall)
                Text("Choose apps whose notifications Murmur can read to you.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSetUp) { Text("Set up") }
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
    icon: ImageVector,
    initial: String?,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // The cursor goes at the end of any text taken from the clipboard.
    var value by remember { mutableStateOf(TextFieldValue(initial.orEmpty(), TextRange(initial.orEmpty().length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                keyboardOptions = if (singleLine) KeyboardOptions(keyboardType = KeyboardType.Uri) else KeyboardOptions.Default,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (singleLine) 0.dp else 160.dp, max = 360.dp)
                    .focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = value.text.isNotBlank(), onClick = { onConfirm(value.text) }) { Text("Read") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How long Undo is offered after deleting a library item. */
private const val UNDO_WINDOW_MS = 15_000L
