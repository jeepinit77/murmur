package app.murmur.share

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.murmur.R
import app.murmur.ui.MainActivity

/**
 * The "Read aloud" Direct Share shortcut. Android shows sharing shortcuts in the share menu's top
 * row, ranked by use, each as its own one-tap target; Murmur's app icon below groups "Read aloud
 * now" with the share options popup and asks which one. The shortcut's share target is declared in
 * res/xml/share_targets.xml and opens [ShareToSpeakActivity].
 *
 * Android also lists the shortcut when the app icon is long-pressed (a shortcut kept out of the
 * launcher never reaches the share menu either). There it opens Murmur and reads the copied text
 * aloud; Android only lets an app read the clipboard while it's on screen.
 */
object ShareShortcuts {
    private const val TAG = "ShareShortcuts"
    private const val READ_ALOUD = "read_aloud"
    private const val CATEGORY = "app.murmur.category.READ_ALOUD"

    /** Publishes (or refreshes) the shortcut. */
    fun publish(context: Context) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, READ_ALOUD)
                .setShortLabel(context.getString(R.string.share_shortcut_label))
                .setLongLived(true)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                // Used from the app icon's long-press menu; the share menu opens the share target.
                .setIntent(Intent(MainActivity.ACTION_READ_CLIPBOARD, null, context, MainActivity::class.java))
                .setCategories(setOf(CATEGORY))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }.onFailure { Log.w(TAG, "Couldn't publish the Read aloud share shortcut", it) }
    }

    /** Tells Android Read aloud was used, so the share menu ranks the shortcut higher. */
    fun reportUsed(context: Context) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, READ_ALOUD) }
    }
}
