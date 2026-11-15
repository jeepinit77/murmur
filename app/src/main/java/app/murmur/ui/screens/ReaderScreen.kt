package app.murmur.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
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
import kotlinx.coroutines.launch

private sealed interface ReaderRow {
    data class Header(val document: Document) : ReaderRow
    data class Heading(val text: String) : ReaderRow
    data class Paragraph(val text: String, val segments: List<Segment>) : ReaderRow
}

private fun buildRows(content: ReaderContent): Pair<List<ReaderRow>, IntArray> {
    val rows = ArrayList<ReaderRow>()
    val rowOfSegment = IntArray(content.segments.size)
    val byParagraph = content.segments.groupBy { it.chapter to it.paragraph }
    val doc = content.document
    if (doc.siteName != null || doc.author != null || doc.published != null || doc.incomplete) rows += ReaderRow.Header(doc)
    content.document.chapters.forEachIndexed { c, chapter ->
        val title = chapter.title
        // EPUB chapters usually repeat their title as the first paragraph; don't show it twice.
        if (!title.isNullOrBlank() && chapter.paragraphs.firstOrNull() != title) rows += ReaderRow.Heading(title)
        chapter.paragraphs.forEachIndexed { p, text ->
            val segs = byParagraph[c to p].orEmpty()
            segs.forEach { rowOfSegment[it.index] = rows.size }
            rows += ReaderRow.Paragraph(text, segs)
        }
    }
    return rows to rowOfSegment
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(docId: String?, onBack: () -> Unit, onVoices: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var chaptersOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var reloading by remember { mutableStateOf(false) }
    val reader = c.reader
    val state by reader.state.collectAsStateWithLifecycle()
    val content by reader.content.collectAsStateWithLifecycle()

    LaunchedEffect(docId) {
        if (docId != null && reader.content.value?.docId != docId) reader.openFromLibrary(docId, autoplay = false)
    }

    val current = content?.takeIf { docId == null || it.docId == docId }
    val layout = remember(current) { current?.let { buildRows(it) } }
    val listState = rememberLazyListState()
    var userScrolled by remember { mutableStateOf(false) }

    // Follow along while speaking, unless the user is scrolling around.
    LaunchedEffect(state.currentIndex, layout) {
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
            TopAppBar(
                title = { Text(current?.document?.title ?: state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackIcon(onBack) },
                actions = {
                    val doc = current?.document
                    if (doc != null && doc.chapters.size >= 2) {
                        IconButton(onClick = { chaptersOpen = true }) { Icon(Icons.AutoMirrored.Filled.List, "Chapters") }
                    }
                    val meta = current?.let { c.library.meta(it.docId) }
                    if (meta != null && meta.source.startsWith("http")) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
        bottomBar = { if (state.docId != null) PlayerBar(state, reader) },
    ) { padding ->
        if (layout == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.error != null) Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                else CircularProgressIndicator()
            }
            return@Scaffold
        }
        val (rows, _) = layout
        val activeSegment = current?.segments?.getOrNull(state.currentIndex)
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
                    is ReaderRow.Header -> ArticleHeader(row.document)
                    is ReaderRow.Heading -> Text(
                        row.text,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    is ReaderRow.Paragraph -> ParagraphText(
                        row = row,
                        active = activeSegment?.takeIf { it in row.segments },
                        onTapSegment = { seg ->
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
            currentChapter = current.segments.getOrNull(state.currentIndex)?.chapter ?: 0,
            speed = state.speed,
            onPick = { chapter ->
                chaptersOpen = false
                reader.seekToChapter(chapter)
            },
            onDismiss = { chaptersOpen = false },
        )
    }
}

/** Site, author and date above a web article, plus a note when it may be cut off. */
@Composable
private fun ArticleHeader(document: Document) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val line = listOfNotNull(document.siteName, document.author, PageMetadata.formatDate(document.published))
        if (line.isNotEmpty()) {
            Text(
                line.joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (document.incomplete) {
            Text(
                "May be incomplete. Sign in to the site from the menu, then reload.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
private fun ParagraphText(row: ReaderRow.Paragraph, active: Segment?, onTapSegment: (Segment) -> Unit) {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    val text: AnnotatedString = remember(row, active, highlight) {
        buildAnnotatedString {
            append(row.text)
            if (active != null && active.end <= row.text.length) {
                addStyle(SpanStyle(background = highlight, color = onHighlight, fontWeight = FontWeight.Medium), active.start, active.end)
            }
        }
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 30.sp),
        onTextLayout = { layoutResult = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .pointerInput(row) {
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
