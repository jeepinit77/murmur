package app.murmur.voice

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.murmur.MurmurApp
import app.murmur.R
import app.murmur.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads a voice pack archive (resumable), verifies it if a checksum is known,
 * and extracts it into filesDir/voices/<id>.
 */
class VoiceDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext.container
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val pack = c.packs.catalog.byId(id) ?: return@withContext fail("Unknown voice $id")

        runCatching { setForeground(foregroundInfo(pack.name, null)) }

        val archive = File(applicationContext.cacheDir, "voice-$id.tar.bz2")
        try {
            download(pack.url, archive, pack.name)
            pack.sha256?.let { expected ->
                report("Verifying", null, pack.name)
                val actual = sha256(archive)
                if (!actual.equals(expected, ignoreCase = true)) {
                    archive.delete()
                    return@withContext fail("Checksum mismatch; please retry")
                }
            }
            report("Unpacking", null, pack.name)
            val tmp = File(c.packs.root, "$id.tmp")
            tmp.deleteRecursively()
            extract(archive, tmp)
            val dest = c.packs.dirFor(id)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IOException("Could not move voice into place")
            archive.delete()
            withContext(Dispatchers.Main) { c.packs.refresh() }
            if (c.packs.filesFor(id) == null) return@withContext fail("Archive did not contain a usable model")
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else fail(e.message ?: "Download failed")
        }
    }

    private suspend fun download(url: String, target: File, name: String) {
        val http = applicationContext.container.http
        val existing = if (target.exists()) target.length() else 0L
        val request = Request.Builder().url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 416) return // already complete
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val resumed = response.code == 206
            val body = response.body ?: throw IOException("Empty response")
            val total = body.contentLength().let { if (it > 0) it + (if (resumed) existing else 0) else -1L }
            var done = if (resumed) existing else 0L
            FileOutputStream(target, resumed).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            report("Downloading", if (total > 0) done.toFloat() / total else null, name)
                        }
                    }
                }
            }
        }
    }

    private suspend fun extract(archive: File, dest: File) {
        dest.mkdirs()
        val canonicalDest = dest.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                // Archives contain a single top-level folder named after the model; strip it.
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val out = File(dest, relative)
                if (!out.canonicalPath.startsWith(canonicalDest)) throw IOException("Unsafe path in archive: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (entry.isFile) {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { copy(tar, it) }
                }
            }
        }
    }

    private fun copy(input: InputStream, out: FileOutputStream) {
        val buf = ByteArray(DEFAULT_BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun report(phase: String, fraction: Float?, name: String) {
        setProgress(workDataOf(KEY_PHASE to phase, KEY_PROGRESS to (fraction ?: -1f)))
        runCatching { setForeground(foregroundInfo(name, fraction, phase)) }
    }

    private fun foregroundInfo(name: String, fraction: Float?, phase: String = "Downloading"): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, MurmurApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$phase $name")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun fail(message: String) = Result.failure(workDataOf(KEY_ERROR to message))

    companion object {
        const val KEY_ID = "id"
        const val KEY_PROGRESS = "progress"
        const val KEY_PHASE = "phase"
        const val KEY_ERROR = "error"
        private const val NOTIFICATION_ID = 2001
        private const val DEFAULT_BUFFER = 64 * 1024
    }
}
