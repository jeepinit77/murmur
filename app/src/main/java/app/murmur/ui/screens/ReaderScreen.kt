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
import app.murmur.container
import app.murmur.playback.ReaderContent
import app.murmur.text.Segment

private sealed interface ReaderRow {
    data class Heading(val text: String) : ReaderRow
    data class Paragraph(val text: String, val segments: List<Segment>) : ReaderRow
}

private fun buildRows(content: ReaderContent): Pair<List<ReaderRow>, IntArray> {
    val rows = ArrayList<ReaderRow>()
    val rowOfSegment = IntArray(content.segments.size)
    val byParagraph = content.segments.groupBy { it.chapter to it.paragraph }
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
fun ReaderScreen(docId: String?, onBack: () -> Unit) {
    val c = LocalContext.current.container
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            itemsIndexed(rows) { _, row ->
                when (row) {
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
