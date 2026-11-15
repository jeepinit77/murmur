package app.murmur.web

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** The play build summarizes tables with Gemini Nano on phones that support it. */
fun tableAi(context: Context, scope: CoroutineScope): TableAi? = GeminiNanoTableAi(scope)
