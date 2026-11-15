package app.murmur.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** The foss build has only the open-source sherpa-onnx voices. */
fun extraVoiceSources(context: Context, scope: CoroutineScope): List<VoiceSource> = emptyList()
