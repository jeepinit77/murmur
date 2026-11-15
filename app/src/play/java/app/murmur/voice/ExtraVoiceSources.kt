package app.murmur.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** The play build adds Google's on-device voices on phones that support them. */
fun extraVoiceSources(context: Context, scope: CoroutineScope): List<VoiceSource> =
    listOf(MlKitVoiceSource(context, scope))
