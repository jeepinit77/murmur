package app.murmur.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.murmur.container
import kotlinx.coroutines.CancellationException

/** Imports a link that was shared while offline, once the network is back. */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val imports = applicationContext.container.imports
        return try {
            imports.completePending(id)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val retryable = (e as? ImportException)?.retryable ?: true
            if (retryable && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            applicationContext.container.library.setStatus(id, DocStatus.FAILED, e.message ?: "Couldn't load the page")
            Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "id"
        private const val MAX_ATTEMPTS = 5
    }
}
