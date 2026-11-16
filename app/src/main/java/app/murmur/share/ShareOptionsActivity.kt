package app.murmur.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.murmur.R
import app.murmur.container
import app.murmur.library.PendingImportException
import app.murmur.playback.ShareAction
import app.murmur.playback.SpeakRequest
import app.murmur.playback.import
import app.murmur.ui.MainActivity
import app.murmur.ui.theme.MurmurTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * The "Murmur" entry in other apps' share menus: a popup over the app you shared from with
 * Add to queue, Save for later and Open in Murmur. "Read aloud now" stays a separate entry
 * that starts reading with no popup.
 */
class ShareOptionsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ShareToSpeakActivity.parse(intent)
        if (request == null) {
            Toast.makeText(this, R.string.nothing_to_read, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val c = container

        setContent {
            MurmurTheme {
                val scope = rememberCoroutineScope()
                // Set while opening: the import in progress and what it's doing.
                var opening by remember { mutableStateOf<Job?>(null) }
                var stage by remember { mutableStateOf<String?>(null) }
                // The title of a shared link that's already in the library.
                var savedTitle by remember { mutableStateOf<String?>(null) }
                val alreadySaved = savedTitle != null
                LaunchedEffect(Unit) {
                    savedTitle = request.linkUrl()?.let { c.imports.savedCopy(it) }?.title
                }

                fun close() {
                    opening?.cancel()
                    request.textFile?.let { File(it).delete() }
                    finish()
                }

                fun open() {
                    opening = scope.launch {
                        try {
                            val outcome = request.import(this@ShareOptionsActivity, onStage = { stage = it })
                            startActivity(
                                Intent(this@ShareOptionsActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_ROUTE, "${MainActivity.ROUTE_READER}/${outcome.meta.id}")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: PendingImportException) {
                            Toast.makeText(this@ShareOptionsActivity, "${e.message}. ${getString(R.string.saved_for_later)}", Toast.LENGTH_LONG).show()
                            finish()
                        } catch (e: Exception) {
                            c.cues.playError()
                            Toast.makeText(this@ShareOptionsActivity, e.message ?: getString(R.string.nothing_to_read), Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }

                // The activity's window is transparent; dim the app underneath like a normal dialog.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    val colors = PopupColors.from(MaterialTheme.colorScheme)
                    Surface(
                        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 440.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(32.dp),
                        color = colors.card,
                        contentColor = colors.text,
                        shadowElevation = 16.dp,
                    ) {
                        Column(Modifier.padding(24.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(Icons.Default.GraphicEq, colors.accent, colors.onAccent, RoundedCornerShape(18.dp), 56)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        savedTitle ?: request.label(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (alreadySaved) {
                                        Text(
                                            getString(R.string.saved_page_title),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.secondaryText,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(colors.closeButton)
                                        .clickable(role = Role.Button, onClick = { close() }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.Close, getString(R.string.close), tint = colors.text, modifier = Modifier.size(22.dp))
                                }
                            }

                            Spacer(Modifier.height(20.dp))
                            val groupShape = RoundedCornerShape(24.dp)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(groupShape)
                                    .background(colors.group)
                                    .border(1.dp, colors.outline, groupShape),
                            ) {
                                if (opening != null) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent, strokeWidth = 3.dp)
                                        Spacer(Modifier.width(16.dp))
                                        Text(
                                            stage ?: getString(R.string.loading),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = colors.secondaryText,
                                        )
                                    }
                                    return@Column
                                }
                                OptionRow(
                                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    label = getString(R.string.share_queue_label),
                                    description = getString(
                                        if (c.reader.isBusy()) R.string.share_queue_description else R.string.share_queue_now_description,
                                    ),
                                    background = Color.Transparent,
                                    iconBackground = colors.accentSoft,
                                    iconTint = colors.text,
                                    iconShape = CircleShape,
                                    colors = colors,
                                    onClick = {
                                        if (ShareToSpeakActivity.checkVoice(this@ShareOptionsActivity)) {
                                            ShareToSpeakActivity.start(this@ShareOptionsActivity, request.copy(action = ShareAction.QUEUE))
                                        }
                                        finish()
                                    },
                                )
                                if (!alreadySaved) {
                                    HorizontalDivider(color = colors.outline)
                                    OptionRow(
                                        icon = Icons.Default.BookmarkAdd,
                                        label = getString(R.string.share_save_label),
                                        description = getString(R.string.share_save_description),
                                        background = Color.Transparent,
                                        iconBackground = colors.goldSoft,
                                        iconTint = colors.onGoldSoft,
                                        iconShape = RoundedCornerShape(16.dp),
                                        colors = colors,
                                        onClick = {
                                            ShareToSpeakActivity.start(this@ShareOptionsActivity, request.copy(action = ShareAction.SAVE))
                                            finish()
                                        },
                                    )
                                }
                                HorizontalDivider(color = colors.outline)
                                OptionRow(
                                    icon = Icons.AutoMirrored.Filled.MenuBook,
                                    label = getString(R.string.share_open_label),
                                    description = getString(R.string.share_open_description),
                                    background = Color.Transparent,
                                    iconBackground = colors.accentSoft,
                                    iconTint = colors.text,
                                    iconShape = RoundedCornerShape(16.dp),
                                    colors = colors,
                                    onClick = { open() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
