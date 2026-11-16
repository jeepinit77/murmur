package app.murmur.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.murmur.library.DocMeta
import app.murmur.library.DocStatus
import app.murmur.ui.library.DocIcon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import app.murmur.container
import app.murmur.playback.ReaderContent
import app.murmur.text.Document
import app.murmur.text.ListeningTime
import app.murmur.text.Segment
import app.murmur.web.PageMetadata
import app.murmur.web.SiteSignInActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.saveable.rememberSaveable
import app.murmur.text.TextMatch
import app.murmur.text.TextSearch
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import app.murmur.text.SentenceSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

private sealed interface ReaderRow {
    data class Header(val document: Document) : ReaderRow
    data class Heading(val text: String) : ReaderRow
    data class Paragraph(val chapter: Int, val paragraph: Int, val text: String, val segments: List<Segment>) : ReaderRow
}

private fun buildRows(content: ReaderContent): Pair<List<ReaderRow>, IntArray> {
    val rows = ArrayList<ReaderRow>()
    val rowOfSegment = IntArray(content.segments.size)
    val byParagraph = content.segments.groupBy { it.chapter to it.paragraph }
    val doc = content.document
    rows += ReaderRow.Header(doc)
    content.document.chapters.forEachIndexed { c, chapter ->
        val title = chapter.title
        // EPUB chapters usually repeat their title as the first paragraph; don't show it twice.
        if (!title.isNullOrBlank() && chapter.paragraphs.firstOrNull() != title) rows += ReaderRow.Heading(title)
        chapter.paragraphs.forEachIndexed { p, text ->
            val segs = byParagraph[c to p].orEmpty()
            // Articles often start with their title, which the header already shows. It's still
            // read aloud; following along stays on the header meanwhile.
            if (c == 0 && p == 0 && text.trim().equals(doc.title.trim(), ignoreCase = true)) {
                segs.forEach { rowOfSegment[it.index] = 0 }
                return@forEachIndexed
            }
            segs.forEach { rowOfSegment[it.index] = rows.size }
            rows += ReaderRow.Paragraph(c, p, text, segs)
        }
    }
    return rows to rowOfSegment
}

/** The first sentence at or after each row, where reading from that row would start. */
private fun firstSegmentOfRows(rows: List<ReaderRow>, segmentCount: Int): IntArray {
    val out = IntArray(rows.size)
    var next = (segmentCount - 1).coerceAtLeast(0)
    for (i in rows.indices.reversed()) {
        val row = rows[i]
        (row as? ReaderRow.Paragraph)?.segments?.firstOrNull()?.let { next = it.index }
        out[i] = if (row is ReaderRow.Header) 0 else next
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(docId: String?, onBack: () -> Unit, onVoices: () -> Unit, initialFind: String? = null) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var chaptersOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var reloading by remember { mutableStateOf(false) }
    val reader = c.reader
    val state by reader.state.collectAsStateWithLifecycle()
    val content by reader.content.collectAsStateWithLifecycle()
    val rewindSeconds by c.settings.rewindSeconds.collectAsStateWithLifecycle()
    val expandRequested by c.playerExpandRequest.collectAsStateWithLifecycle()
    // The player starts collapsed, except when the app is opened from the playback notification.
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    // Only the now-playing screen (docId == null), which is where the notification leads, takes the
    // request; an article screen underneath it would otherwise use it up first.
    LaunchedEffect(expandRequested) {
        if (expandRequested && docId == null) {
            playerExpanded = true
            c.playerExpandRequest.value = false
        }
    }

    // Opening an article never interrupts what's playing: while something else plays, the article
    // is loaded just for reading. Otherwise it goes into the player, ready to listen.
    var readingCopy by remember(docId) { mutableStateOf<ReaderContent?>(null) }
    LaunchedEffect(docId) {
        if (docId == null || reader.content.value?.docId == docId) return@LaunchedEffect
        if (reader.isBusy()) {
            val doc = c.library.load(docId) ?: return@LaunchedEffect
            val segments = withContext(Dispatchers.Default) { SentenceSegmenter().segment(doc) }
            readingCopy = ReaderContent(docId, doc, segments)
        } else {
            reader.openFromLibrary(docId, autoplay = false)
        }
    }

    val inPlayer = content?.takeIf { docId == null || it.docId == docId }
    val current = inPlayer ?: readingCopy
    // Listening: this article is playing. Otherwise the screen is for reading, and scrolling moves
    // the place where listening would start.
    val listening = inPlayer != null && state.docId == inPlayer.docId && state.playWhenReady && !state.ended
    val layout = remember(current) { current?.let { buildRows(it) } }
    val rowStarts = remember(layout) { layout?.let { firstSegmentOfRows(it.first, current?.segments?.size ?: 0) } }
    val listState = rememberLazyListState()
    val styles = rememberArticleStyles()
    var textSheetOpen by remember { mutableStateOf(false) }
    // Set when scrolling while reading moved the place; the highlight stays hidden until listening resumes.
    var movedByReading by remember(docId) { mutableStateOf(false) }

    /** The sentence at the top of the screen. */
    fun readingIndex(): Int {
        val starts = rowStarts ?: return 0
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0
        // A row mostly scrolled off the top counts as read.
        val row = if (-first.offset > first.size / 2) first.index + 1 else first.index
        return starts.getOrElse(row) { starts.lastOrNull() ?: 0 }
    }

    // Start at the saved place: where listening stopped or reading left off.
    var placed by rememberSaveable(docId) { mutableStateOf(false) }
    LaunchedEffect(layout != null) {
        val l = layout ?: return@LaunchedEffect
        if (placed) return@LaunchedEffect
        placed = true
        if (initialFind != null || listening) return@LaunchedEffect
        val index = if (inPlayer != null) state.currentIndex else c.library.progressOf(current!!.docId)
        val row = l.second.getOrNull(index) ?: 0
        if (row > 0) listState.scrollToItem(row)
    }

    // While reading, the sentence at the top of the screen becomes the place to continue from,
    // for listening and in the library. Reaching the end marks the article as done.
    val inPlayerNow by rememberUpdatedState(inPlayer != null)
    LaunchedEffect(layout, listening) {
        val l = layout ?: return@LaunchedEffect
        val id = current?.docId ?: return@LaunchedEffect
        if (listening) {
            movedByReading = false
            return@LaunchedEffect
        }
        snapshotFlow { if (listState.isScrollInProgress) null else readingIndex() }
            .filterNotNull()
            .distinctUntilChanged()
            .drop(1) // where the screen already was
            .collect { index ->
                val saved = if (inPlayerNow) reader.state.value.currentIndex else c.library.progressOf(id)
                if (l.second.getOrNull(saved) != l.second.getOrNull(index)) {
                    if (inPlayerNow) reader.seekTo(index) else c.library.setProgress(id, index)
                    movedByReading = true
                }
                if (!listState.canScrollForward) c.library.setFinished(id, true)
            }
    }

    val keepScreenOn by c.settings.keepScreenOn.collectAsStateWithLifecycle()
    if (keepScreenOn && current != null && !listening) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    fun listenFromHere() {
        val cur = current ?: return
        if (!c.voices.hasAnyInstalled()) {
            onVoices()
            return
        }
        val index = readingIndex()
        scope.launch { reader.open(cur.docId, cur.document, startIndex = index, autoplay = true) }
    }
    val headerHidden by remember(layout) { derivedStateOf { layout == null || listState.firstVisibleItemIndex > 0 } }
    var userScrolled by remember { mutableStateOf(false) }

    // Find in article: null when the find bar is closed.
    var findQuery by rememberSaveable { mutableStateOf(initialFind) }
    var findIndex by rememberSaveable { mutableStateOf(0) }
    val matches = remember(current, findQuery) {
        val q = findQuery
        if (current == null || q.isNullOrBlank()) emptyList() else TextSearch.find(current.document, q, limit = MAX_FIND_MATCHES)
    }
    val matchesByParagraph = remember(matches) { matches.groupBy { it.chapter to it.paragraph } }
    val rowOfParagraph = remember(layout) {
        layout?.first?.withIndex()?.mapNotNull { (i, row) -> (row as? ReaderRow.Paragraph)?.let { (it.chapter to it.paragraph) to i } }
            ?.toMap().orEmpty()
    }
    // A new query starts at the first match from the top of the screen down.
    LaunchedEffect(matches) {
        val top = listState.firstVisibleItemIndex
        findIndex = matches.indexOfFirst { (rowOfParagraph[it.chapter to it.paragraph] ?: 0) >= top }.coerceAtLeast(0)
    }
    LaunchedEffect(findIndex, matches) {
        val m = matches.getOrNull(findIndex) ?: return@LaunchedEffect
        val row = rowOfParagraph[m.chapter to m.paragraph] ?: return@LaunchedEffect
        userScrolled = true // stop following the voice until it comes back into view
        if (row !in listState.layoutInfo.visibleItemsInfo.map { it.index }.drop(1).dropLast(1)) {
            listState.animateScrollToItem(row, scrollOffset = -120)
        }
    }
    fun stepMatch(delta: Int) {
        if (matches.isNotEmpty()) findIndex = (findIndex + delta).mod(matches.size)
    }
    BackHandler(enabled = findQuery != null) { findQuery = null }

    // Follow along while speaking, unless the user is scrolling around.
    LaunchedEffect(state.currentIndex, layout, listening) {
        if (!listening) return@LaunchedEffect
        val rowIndex = layout?.second?.getOrNull(state.currentIndex) ?: return@LaunchedEffect
        if (listState.isScrollInProgress) {
            userScrolled = true
            return@LaunchedEffect
        }
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (!userScrolled || rowIndex in visible) {
            userScrolled = false
            if (rowIndex !in visible.drop(1).dropLast(2)) listState.animateScrollToItem(rowIndex, scrollOffset = -120)
        }
    }

    Scaffold(
        topBar = {
            val query = findQuery
            if (query != null) {
                SearchTopBar(
                    query = query,
                    placeholder = "Find in article",
                    onQueryChange = { findQuery = it },
                    onClose = { findQuery = null },
                    onSearch = { stepMatch(1) },
                    actions = {
                        if (query.isNotBlank()) {
                            Text(
                                if (matches.isEmpty()) "0/0" else "${findIndex + 1}/${matches.size}${if (matches.size >= MAX_FIND_MATCHES) "+" else ""}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { stepMatch(-1) }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Default.KeyboardArrowUp, "Previous match")
                        }
                        IconButton(onClick = { stepMatch(1) }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Default.KeyboardArrowDown, "Next match")
                        }
                    },
                )
                return@Scaffold
            }
            TopAppBar(
                title = {
                    AnimatedVisibility(visible = headerHidden, enter = fadeIn(), exit = fadeOut()) {
                        Text(current?.document?.title ?: state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackIcon(onBack) },
                actions = {
                    val doc = current?.document
                    if (doc != null) {
                        IconButton(onClick = { findQuery = "" }) { Icon(Icons.Default.Search, "Find in article") }
                        IconButton(onClick = { textSheetOpen = true }) { Icon(Icons.Default.FormatSize, "Text settings") }
                    }
                    if (doc != null && doc.chapters.size >= 2) {
                        IconButton(onClick = { chaptersOpen = true }) { Icon(Icons.AutoMirrored.Filled.List, "Chapters") }
                    }
                    val meta = current?.let { c.library.meta(it.docId) }
                    if (meta?.source?.startsWith("http") == true) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (meta != null && meta.source.startsWith("http")) {
                            DropdownMenuItem(text = { Text("Open original") }, onClick = {
                                menuOpen = false
                                openInBrowser(context, meta.source)
                            })
                            if (meta.isWeb) {
                                DropdownMenuItem(text = { Text("Reload") }, onClick = {
                                    menuOpen = false
                                    reloading = true
                                    scope.launch {
                                        try {
                                            val (outcome, index) = c.imports.reload(meta.id)
                                            reader.replaceDocument(meta.id, outcome.document, index)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, e.message ?: "Couldn't reload the article", Toast.LENGTH_LONG).show()
                                        } finally {
                                            reloading = false
                                        }
                                    }
                                })
                            }
                            DropdownMenuItem(text = { Text("Sign in to site") }, onClick = {
                                menuOpen = false
                                context.startActivity(SiteSignInActivity.intent(context, meta.source, meta.id))
                            })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // Another item is playing: Play switches the player to this one, from where the reader is.
            if (inPlayer == null && current != null) {
                ExtendedFloatingActionButton(
                    onClick = { listenFromHere() },
                    expanded = !listState.lastScrolledForward,
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    text = { Text("Play") },
                )
            }
        },
        bottomBar = {
            // Hidden while reading an article that isn't the one in the player, so the screen is
            // only about this article. What's playing carries on, with its notification controls.
            if (state.docId != null && (docId == null || inPlayer != null)) {
                val playing = c.library.meta(state.docId!!)
                NowPlayingBar(
                    state = state,
                    content = content,
                    reader = reader,
                    rewindSeconds = rewindSeconds,
                    expanded = playerExpanded,
                    onExpandedChange = { playerExpanded = it },
                    onVoices = onVoices,
                    source = playing?.source,
                    kind = playing?.kind,
                )
            }
        },
    ) { padding ->
        if (layout == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.error != null) Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                else CircularProgressIndicator()
            }
            return@Scaffold
        }
        val (rows, _) = layout
        val activeSegment = current?.takeIf { inPlayer != null && (listening || !movedByReading) }
            ?.segments?.getOrNull(state.currentIndex)
        Column(Modifier.fillMaxSize().padding(padding)) {
        if (reloading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.notice?.let { notice ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onVoices) { Text("Voices") }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            itemsIndexed(rows) { _, row ->
                when (row) {
                    is ReaderRow.Header -> ArticleHeader(row.document, current?.let { c.library.meta(it.docId) }, state.speed)
                    is ReaderRow.Heading -> Text(
                        row.text,
                        style = styles.heading,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    is ReaderRow.Paragraph -> ParagraphText(
                        row = row,
                        active = activeSegment?.takeIf { it in row.segments },
                        found = matchesByParagraph[row.chapter to row.paragraph].orEmpty(),
                        currentFound = matches.getOrNull(findIndex),
                        style = styles.body,
                        // Tapping a sentence listens from there, but never takes over another item that's playing.
                        onTapSegment = if (inPlayer == null) null else { seg ->
                            reader.seekTo(seg.index)
                            if (!state.playWhenReady) reader.play()
                        },
                    )
                }
            }
        }
        }
    }

    if (chaptersOpen && current != null) {
        ChapterSheet(
            content = current,
            currentChapter = current.segments.getOrNull(if (listening) state.currentIndex else readingIndex())?.chapter ?: 0,
            speed = state.speed,
            onPick = { chapter ->
                chaptersOpen = false
                if (listening) {
                    reader.seekToChapter(chapter)
                } else {
                    val segment = current.segments.firstOrNull { it.chapter >= chapter }
                    val row = segment?.let { layout?.second?.getOrNull(it.index) }
                    if (row != null) scope.launch { listState.scrollToItem(row) }
                }
            },
            onDismiss = { chaptersOpen = false },
        )
    }
    if (textSheetOpen) {
        ModalBottomSheet(onDismissRequest = { textSheetOpen = false }) {
            Text(
                "Text settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 24.dp)) { ReaderTextControls() }
        }
    }
}

/**
 * The top of the document: the site's icon and name, the title, then author, date and listening
 * time, plus a note when a web article may be cut off.
 */
@Composable
private fun ArticleHeader(document: Document, meta: DocMeta?, speed: Float) {
    val colors = MaterialTheme.colorScheme
    val words = remember(document) { document.chapters.sumOf { ch -> ch.paragraphs.sumOf { ListeningTime.words(it) } } }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        val site = document.siteName
        if (site != null && meta != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                DocIcon(meta.source, meta.kind, DocStatus.READY, size = 24.dp)
                Text(
                    site,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        Text(document.title, style = MaterialTheme.typography.headlineMedium)
        val time = "${ListeningTime.shortLabel(words, speed)} listen · ${ListeningTime.readingLabel(words)} read"
        val line = listOfNotNull(document.author, PageMetadata.formatDate(document.published), time)
        Text(
            line.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (document.incomplete) {
            Surface(
                color = colors.tertiaryContainer,
                contentColor = colors.onTertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text(
                    "May be incomplete. Sign in to the site from the menu, then reload.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 4.dp), color = colors.outlineVariant)
    }
}

/** Lists the document's chapters with listening times; tapping one jumps to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    content: ReaderContent,
    currentChapter: Int,
    speed: Float,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val words = remember(content) {
        content.document.chapters.map { ch -> ch.paragraphs.sumOf { ListeningTime.words(it) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Chapters",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(content.document.chapters) { i, chapter ->
                val title = chapter.title?.takeIf { it.isNotBlank() } ?: if (i == 0) "Introduction" else "Chapter ${i + 1}"
                val selected = i == currentChapter
                ListItem(
                    modifier = Modifier.clickable { onPick(i) },
                    headlineContent = {
                        Text(
                            title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else null,
                        )
                    },
                    supportingContent = { Text(ListeningTime.label(words[i], speed)) },
                    colors = if (selected) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                )
            }
        }
    }
}

@Composable
private fun ParagraphText(
    row: ReaderRow.Paragraph,
    active: Segment?,
    found: List<TextMatch>,
    currentFound: TextMatch?,
    style: TextStyle,
    onTapSegment: ((Segment) -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val text: AnnotatedString = remember(row, active, found, currentFound, scheme) {
        buildAnnotatedString {
            append(row.text)
            for (m in found) {
                if (m.end > row.text.length) continue
                val style = if (m == currentFound) SpanStyle(background = scheme.tertiary, color = scheme.onTertiary)
                else SpanStyle(background = scheme.tertiaryContainer, color = scheme.onTertiaryContainer)
                addStyle(style, m.start, m.end)
            }
        }
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val highlight = scheme.primaryContainer.copy(alpha = 0.75f)
    Text(
        text = text,
        style = style,
        onTextLayout = { layoutResult = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .drawBehind {
                val layout = layoutResult ?: return@drawBehind
                if (active == null || active.end > row.text.length) return@drawBehind
                // One rounded bar per line, a little wider than the words and inset from the line
                // height, so consecutive lines read as separate bars.
                val padX = 4.dp.toPx()
                val insetY = 3.dp.toPx()
                val radius = CornerRadius(6.dp.toPx())
                val first = layout.getLineForOffset(active.start)
                val last = layout.getLineForOffset((active.end - 1).coerceAtLeast(active.start))
                for (line in first..last) {
                    val left = if (line == first) layout.getHorizontalPosition(active.start, true) else layout.getLineLeft(line)
                    val right = if (line == last) layout.getHorizontalPosition(active.end, true) else layout.getLineRight(line)
                    drawRoundRect(
                        color = highlight,
                        topLeft = Offset(minOf(left, right) - padX, layout.getLineTop(line) + insetY),
                        size = Size(kotlin.math.abs(right - left) + 2 * padX, layout.getLineBottom(line) - layout.getLineTop(line) - 2 * insetY),
                        cornerRadius = radius,
                    )
                }
            }
            .pointerInput(row, onTapSegment) {
                if (onTapSegment == null) return@pointerInput
                detectTapGestures { pos ->
                    val offset = layoutResult?.getOffsetForPosition(pos) ?: return@detectTapGestures
                    val seg = row.segments.firstOrNull { offset in it.start until it.end }
                        ?: row.segments.lastOrNull { it.start <= offset }
                        ?: row.segments.firstOrNull()
                    seg?.let(onTapSegment)
                }
            },
    )
}

/** Find in article stops counting here; very common words in long books would otherwise slow it down. */
private const val MAX_FIND_MATCHES = 1000
