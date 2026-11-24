package com.group41.kotlinapp.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.group41.kotlinapp.App
import com.group41.kotlinapp.AppPreferences
import com.group41.kotlinapp.model.PendingAction
import com.group41.kotlinapp.model.PendingActionType
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.repository.PendingActionRepository
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Vacía el buzón de acciones pendientes: manda al backend lo que el usuario hizo sin internet.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()

        val userId = ApiClient.tokens.userId ?: return Result.success()

        val queue = PendingActionRepository(app.database)
        val pending = queue.pendingFor(userId)
        if (pending.isEmpty()) return Result.success()

        var shouldRetryLater = false
        var allActionsSucceeded = true

        for (action in pending) {
            // If the account changes mid-run, leave the remaining actions queued
            // until their owner signs in again.
            if (ApiClient.tokens.userId != userId) return Result.success()
            if (action.type == PendingActionType.JOIN_ACTIVITY || action.type == PendingActionType.CANCEL_ENROLLMENT) {
                // The repository confirms or restores the enrollment when it removes
                // the action. WorkManager retries temporary failures.
                try {
                    if (!queue.syncEnrollment(action) { ApiClient.tokens.userId == userId }) {
                        allActionsSucceeded = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    allActionsSucceeded = false
                    shouldRetryLater = true
                }
                continue
            }
            val outcome = sendCreate(action, queue)
            // A rejected or discarded action is still a failed synchronization, even
            // when removing it leaves the queue empty. Do not report that as success.
            if (outcome != Outcome.DONE) allActionsSucceeded = false
            when (outcome) {
                Outcome.DONE -> queue.markSynced(action)
                Outcome.REJECTED -> queue.discard(action)
                Outcome.RETRY_LATER -> {
                    if (queue.recordFailure(action)) shouldRetryLater = true
                }
                Outcome.UNPROCESSABLE -> queue.discard(action)
                // Retain the row and JPEG without requesting an automatic retry.
                Outcome.KEEP_PENDING -> Unit
            }
        }

        // Read the actual database rows: pendingFor() skips malformed or unknown actions,
        // and new actions may have arrived while this worker was sending the original list.
        // An empty initial queue returns above because no synchronization took place.
        if (allActionsSucceeded && app.database.pendingActionDao().getPendingActions(userId).isEmpty() &&
            ApiClient.tokens.userId == userId) {
            AppPreferences(app).saveLastSuccessfulSync(System.currentTimeMillis(), expectedUserId = userId)
        }

        return if (shouldRetryLater) Result.retry() else Result.success()
    }

    /**
     * Reconstruye la petición de creación desde el payload JSON y la envía al backend.
     */
    private suspend fun sendCreate(action: PendingAction, queue: PendingActionRepository): Outcome {
        return try {
            // After HTTP 201, the repository saves the backend ID and local photo path
            // in an UPLOAD_ACTIVITY_PHOTO row before uploading. Once saved, retries only upload.
            // An interruption before that save still needs backend idempotency.
            queue.syncActivityCreation(action)
            Outcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            creationFailureOutcome(e)
        }
    }

    /** Possible results, including retained actions that need correction before retrying. */
    internal enum class Outcome {
        DONE,
        REJECTED,
        RETRY_LATER,
        UNPROCESSABLE,
        KEEP_PENDING
    }

    companion object {
        /** Retry known temporary failures; preserve validation failures and uncertain data. */
        internal fun creationFailureOutcome(error: Exception): Outcome = when (error) {
            is HttpException -> when (error.code()) {
                // Authentication may recover after refresh; timeouts/rate limits are temporary.
                401, 408, 425, 429 -> Outcome.RETRY_LATER
                in 500..599 -> Outcome.RETRY_LATER
                // Includes 400/422. Keep the payload and JPEG for a later explicit sync
                // after correction, without starting an endless WorkManager retry loop.
                else -> Outcome.KEEP_PENDING
            }
            is IOException -> Outcome.RETRY_LATER
            // Missing IDs/files and parsing failures cannot safely justify discarding data.
            else -> Outcome.KEEP_PENDING
        }
    }
}
