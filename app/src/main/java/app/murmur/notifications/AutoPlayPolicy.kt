package app.murmur.notifications

import android.app.NotificationManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import app.murmur.settings.AutoPlayScreen
import app.murmur.settings.Settings

/** Decides whether a notification from an app may be read without pressing Play, using that app's rule. */
class AutoPlayPolicy(private val context: Context, private val settings: Settings) {

    fun allows(pkg: String): Boolean {
        if (pkg !in settings.autoPlayApps.value) return false
        val rule = settings.readingRule(pkg)
        if (rule.headphonesOnly && !privateAudio()) return false
        if (rule.respectDnd && doNotDisturb()) return false
        val ringer = context.getSystemService(AudioManager::class.java).ringerMode
        if (rule.respectSilent && ringer == AudioManager.RINGER_MODE_SILENT) return false
        if (rule.respectVibrate && ringer == AudioManager.RINGER_MODE_VIBRATE) return false
        val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        return when (rule.screen) {
            AutoPlayScreen.SCREEN_ON -> screenOn
            AutoPlayScreen.SCREEN_OFF -> !screenOn
            AutoPlayScreen.ANY -> true
        }
    }

    private fun doNotDisturb(): Boolean =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

    /** Headphones, a Bluetooth device (including car audio) or a hearing aid is connected. */
    private fun privateAudio(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            types += AudioDeviceInfo.TYPE_BLE_HEADSET
            types += AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }
}
