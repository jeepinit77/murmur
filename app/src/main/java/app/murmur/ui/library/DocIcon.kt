package app.murmur.ui.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import app.murmur.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import app.murmur.library.DocStatus
import app.murmur.library.Importer
import kotlin.math.PI
import kotlin.math.sin

/**
 * A rounded tile identifying an item: the site's icon for web pages when one has been
 * downloaded, otherwise an icon for the kind of item.
 */
@Composable
fun DocIcon(source: String, kind: String, status: DocStatus, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val icons = LocalContext.current.container.siteIcons
    val updates by icons.updates.collectAsStateWithLifecycle()
    val bitmap by produceState<ImageBitmap?>(icons.cached(source), source, updates) {
        value = icons.load(source)
        if (value == null && status == DocStatus.READY) icons.fetch(source)
    }
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val site = bitmap
    if (site != null && status == DocStatus.READY) {
        Box(
            modifier
                .size(size)
                .clip(shape)
                // Site icons are drawn for light backgrounds, so they always sit on white.
                .background(Color.White)
                .border(1.dp, colors.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            Image(site, contentDescription = null, modifier = Modifier.size(size * 0.62f).clip(MaterialTheme.shapes.extraSmall))
        }
        return
    }
    val (icon, container, content) = when {
        status == DocStatus.PENDING -> Triple(Icons.Default.CloudOff, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        status == DocStatus.FAILED -> Triple(Icons.Default.ErrorOutline, colors.errorContainer, colors.onErrorContainer)
        kind == Importer.KIND_PDF -> Triple(Icons.Default.PictureAsPdf, colors.primaryContainer, colors.onPrimaryContainer)
        kind == Importer.KIND_EPUB -> Triple(Icons.Default.MenuBook, colors.primaryContainer, colors.onPrimaryContainer)
        kind == Importer.KIND_WEB -> Triple(Icons.Default.Link, colors.primaryContainer, colors.onPrimaryContainer)
        else -> Triple(Icons.Default.Description, colors.primaryContainer, colors.onPrimaryContainer)
    }
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** Three bars that move while [playing], marking the item being read. */
@Composable
fun Equalizer(playing: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val phase = if (playing) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        ).value
    } else {
        null
    }
    Canvas(modifier.size(20.dp)) {
        val barWidth = size.width / 5
        val still = floatArrayOf(0.55f, 1f, 0.75f)
        for (i in 0 until 3) {
            val level = phase?.let { 0.3f + 0.7f * (0.5f + 0.5f * sin(it * (1 + i * 0.35f) - i * 1.7f)) } ?: still[i]
            val h = size.height * level
            drawRoundRect(
                color = color,
                topLeft = Offset(i * barWidth * 2, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

/** Murmur's logo: the launcher icon, the yellow speech bubble on navy, whatever the app's colors. */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(colorResource(R.color.ic_background)),
    ) {
        Icon(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Color.Unspecified,
            // The icon's drawing fills the middle of a canvas sized for launcher masks; scale it up a little.
            modifier = Modifier.fillMaxSize().scale(1.25f),
        )
    }
}
