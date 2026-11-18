package app.murmur.voice

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.murmur.voices.VoiceCatalog
import app.murmur.voices.VoiceFiles
import app.murmur.voices.VoicePack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

/** Catalog of downloadable sherpa-onnx voice packs plus which are installed on this device. */
class VoiceRepository(private val context: Context) {

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

    /** Removes a downloaded voice; its files (hundreds of MB) are deleted off the main thread. */
    fun delete(id: String) {
        val dir = dirFor(id)
        // Renamed first, so the voice disappears from the list at once and a crash mid-delete
        // leaves a folder the scan ignores rather than a half-deleted voice.
        val doomed = File(dir.parentFile, "${dir.name}.deleting-${System.nanoTime()}.tmp")
        val target = if (dir.renameTo(doomed)) doomed else dir
        refresh()
        Thread({ target.deleteRecursively(); if (target === dir) android.os.Handler(android.os.Looper.getMainLooper()).post { refresh() } }, "VoiceDelete").start()
    }

    private fun scan(): Set<String> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith(".tmp") && VoiceFiles.discover(it) != null }
            .map { it.name }
            .toSet()

    private fun workName(id: String) = "voice-$id"
}
