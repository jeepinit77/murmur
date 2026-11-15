package app.murmur.web

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** The foss build has no on-device model, so tables are read by [TableReader]. */
fun tableAi(context: Context, scope: CoroutineScope): TableAi? = null
