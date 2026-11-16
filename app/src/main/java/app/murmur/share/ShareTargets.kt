package app.murmur.share

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Turns the extra share menu entries ("Add to queue", "Save for later") on and off. */
object ShareTargets {
    const val ADD_TO_QUEUE = "app.murmur.share.AddToQueue"
    const val SAVE_FOR_LATER = "app.murmur.share.SaveForLater"

    fun isShown(context: Context, target: String): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, target))) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> true // enabled, or the manifest default (enabled)
        }

    fun setShown(context: Context, target: String, shown: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context, target),
            if (shown) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun component(context: Context, target: String) = ComponentName(context.packageName, target)
}
