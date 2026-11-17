package app.murmur.share

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ColorScheme
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.murmur.R
import app.murmur.container
import app.murmur.playback.SpeakRequest
import app.murmur.settings.SavedPageMode
import app.murmur.ui.theme.MurmurTheme
import java.io.File

/**
 * Asks what to do with a shared web page that's already in the library: resume the saved copy,
 * or import the page again and start from the beginning. Tapping an option acts on it right
 * away. Shown over the app the user shared from, since sharing doesn't open Murmur.
 */
class SavedPageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = SpeakRequest.fromServiceIntent(intent) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        setContent {
            MurmurTheme {
                var dontAsk by rememberSaveable { mutableStateOf(false) }
                fun choose(mode: SavedPageMode) {
                    if (dontAsk) container.settings.setSavedPageMode(mode)
                    ShareToSpeakActivity.start(this, request.copy(importAgain = mode == SavedPageMode.IMPORT_AGAIN))
                    finish()
                }
                // The activity's window is transparent; dim the app underneath like a normal dialog.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                Dialog(
                    onDismissRequest = { cancel(request) },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    SavedPageDialog(
                        title = title,
                        dontAsk = dontAsk,
                        onDontAskChange = { dontAsk = it },
                        onResume = { choose(SavedPageMode.RESUME) },
                        onImportAgain = { choose(SavedPageMode.IMPORT_AGAIN) },
                        onClose = { cancel(request) },
                    )
                }
            }
        }
    }

    /** Closed without choosing: nothing is read, and a large share's cache file is removed. */
    private fun cancel(request: SpeakRequest) {
        request.sharedTextFile(this)?.delete()
        finish()
    }

    @Composable
    private fun SavedPageDialog(
        title: String,
        dontAsk: Boolean,
        onDontAskChange: (Boolean) -> Unit,
        onResume: () -> Unit,
        onImportAgain: () -> Unit,
        onClose: () -> Unit,
    ) {
        val colors = PopupColors.from(MaterialTheme.colorScheme)
        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = colors.card,
            contentColor = colors.text,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                // Header: icon, title and close button.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Filled.Bookmark, colors.gold, colors.onGold, RoundedCornerShape(18.dp), 56)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        getString(R.string.saved_page_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colors.closeButton)
                            .clickable(role = Role.Button, onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = getString(R.string.close), tint = colors.text, modifier = Modifier.size(22.dp))
                    }
                }

                // The page title stands out from the rest of the message.
                Spacer(Modifier.height(20.dp))
                val message = getString(R.string.saved_page_message, title)
                val titleStart = message.indexOf(title)
                Text(
                    buildAnnotatedString {
                        append(message)
                        if (title.isNotEmpty() && titleStart >= 0) {
                            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.text), titleStart, titleStart + title.length)
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.secondaryText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                // Both choices in one box. Tapping a row acts right away.
                Spacer(Modifier.height(20.dp))
                val groupShape = RoundedCornerShape(24.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(groupShape)
                        .background(colors.group)
                        .border(1.dp, colors.outline, groupShape),
                ) {
                    OptionRow(
                        icon = Icons.Default.PlayArrow,
                        label = getString(R.string.resume),
                        description = getString(R.string.resume_description),
                        background = colors.accentSoft,
                        iconBackground = colors.accent,
                        iconTint = colors.onAccent,
                        iconShape = CircleShape,
                        colors = colors,
                        onClick = onResume,
                    )
                    HorizontalDivider(color = colors.outline)
                    OptionRow(
                        icon = Icons.Default.Refresh,
                        label = getString(R.string.import_again),
                        description = getString(R.string.import_again_description),
                        background = Color.Transparent,
                        iconBackground = colors.goldSoft,
                        iconTint = colors.onGoldSoft,
                        iconShape = RoundedCornerShape(16.dp),
                        colors = colors,
                        onClick = onImportAgain,
                    )
                }

                // Right-aligned so it's within easy reach of the thumb.
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .toggleable(value = dontAsk, role = Role.Checkbox, onValueChange = onDontAskChange)
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(getString(R.string.dont_ask_again), style = MaterialTheme.typography.bodyLarge, color = colors.text)
                        Checkbox(
                            checked = dontAsk,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.accent,
                                checkmarkColor = colors.onAccent,
                                uncheckedColor = colors.secondaryText,
                            ),
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp).size(40.dp),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, request: SpeakRequest, title: String): Intent =
            Intent(context, SavedPageActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .also { request.writeTo(context, it) }
    }
}
