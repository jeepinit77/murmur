package app.murmur.voice

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.murmur.settings.Settings
import app.murmur.settings.VoiceSelection
import app.murmur.voices.VoiceCatalog
import app.murmur.voices.VoiceFiles
import app.murmur.voices.VoicePack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Queued : DownloadState
    /** [fraction] is null while extracting or when the size is unknown. */
    data class Running(val fraction: Float?, val phase: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Catalog of downloadable voices plus what is installed on this device. */
class VoiceRepository(private val context: Context, private val settings: Settings) {

    val catalog: VoiceCatalog by lazy {
        VoiceCatalog.parse(context.assets.open("voices.json").bufferedReader().use { it.readText() })
    }

    val root: File = File(context.filesDir, "voices").apply { mkdirs() }

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    fun dirFor(id: String) = File(root, id)

    fun filesFor(id: String): VoiceFiles? = VoiceFiles.discover(dirFor(id))

    fun isInstalled(id: String) = id in _installed.value

    fun hasAnyInstalled() = _installed.value.isNotEmpty()

    fun installedPacks(): List<VoicePack> = catalog.voices.filter { it.id in _installed.value }

    fun refresh() {
        _installed.value = scan()
        // First installed voice becomes the default so share-to-speak works immediately.
        val current = settings.voice.value
        if (current == null || !isInstalled(current.packId)) {
            installedPacks().firstOrNull()?.let { settings.setVoice(VoiceSelection(it.id, it.defaultSpeaker)) }
        }
    }

    /** Resolves [selection] to an installed pack, falling back to any installed voice. */
    fun resolve(selection: VoiceSelection?): Pair<VoicePack, Int>? {
        if (selection != null) {
            val pack = catalog.byId(selection.packId)
            if (pack != null && isInstalled(pack.id)) return pack to selection.speaker
        }
        val fallback = installedPacks().firstOrNull() ?: return null
        return fallback to fallback.defaultSpeaker
    }

    fun download(id: String) {
        val request = OneTimeWorkRequestBuilder<VoiceDownloadWorker>()
            .setInputData(workDataOf(VoiceDownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("voice-download")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    fun downloadState(id: String): Flow<DownloadState> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(id)).map { infos ->
            val info = infos.firstOrNull() ?: return@map DownloadState.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued
                WorkInfo.State.RUNNING -> {
                    val f = info.progress.getFloat(VoiceDownloadWorker.KEY_PROGRESS, -1f)
                    val phase = info.progress.getString(VoiceDownloadWorker.KEY_PHASE) ?: "Downloading"
                    DownloadState.Running(f.takeIf { it >= 0f }, phase)
                }
                WorkInfo.State.FAILED -> DownloadState.Failed(
                    info.outputData.getString(VoiceDownloadWorker.KEY_ERROR) ?: "Download failed",
                )
                else -> DownloadState.Idle
            }
        }

    fun delete(id: String) {
        dirFor(id).deleteRecursively()
        refresh()
    }

    private fun scan(): Set<String> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith(".tmp") && VoiceFiles.discover(it) != null }
            .map { it.name }
            .toSet()

    private fun workName(id: String) = "voice-$id"
}
