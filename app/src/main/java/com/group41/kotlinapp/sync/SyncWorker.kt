package com.group41.kotlinapp.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.group41.kotlinapp.App
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.model.PendingAction
import com.group41.kotlinapp.model.PendingActionType
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.CreateActivityRequest
import com.group41.kotlinapp.repository.PendingActionRepository
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.ActivityChanges
import com.group41.kotlinapp.cache.ActivityMemoryCache
import com.group41.kotlinapp.data.local.toActivity
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

        val queue = PendingActionRepository(app.database.pendingActionDao())
        val activities = app.database.activityDao()
        val pending = queue.pendingFor(userId)
        if (pending.isEmpty()) return Result.success()

        var shouldRetryLater = false

        for (action in pending) {
            when (send(action, activities)) {
                Outcome.DONE -> queue.markSynced(action)
                Outcome.REJECTED -> queue.discard(action)
                Outcome.RETRY_LATER -> {
                    if (queue.recordFailure(action)) shouldRetryLater = true
                }
                Outcome.UNPROCESSABLE -> queue.discard(action)
            }
        }

        return if (shouldRetryLater) Result.retry() else Result.success()
    }

    /**
     * Intenta ejecutar una acción contra el backend y dice cómo le fue.
     */
    private suspend fun send(action: PendingAction, activities: ActivityDao): Outcome =
        when (action.type) {
            PendingActionType.JOIN_ACTIVITY -> sendJoin(action, activities)
            PendingActionType.CREATE_ACTIVITY -> sendCreate(action)
        }

    private suspend fun sendJoin(action: PendingAction, activities: ActivityDao): Outcome {
        val payload = JoinPayload.fromJson(action.payload) ?: return Outcome.UNPROCESSABLE

        return try {
            ApiClient.api.join(
                activityId = payload.activityId,
                recommendationId = payload.recommendationId,
                freeTimeMinutes = payload.freeTimeMinutes
            )

            confirmJoin(payload.activityId, activities)
            Outcome.DONE

        } catch (e: CancellationException) {
            // Permite la cancelación limpia de la corrutina por parte de WorkManager
            throw e

        } catch (e: HttpException) {
            when {
                e.code() == 409 -> {
                    val remote = ActivityRepository(activities).getActivityById(payload.activityId, serverOnly = true)
                    if (remote?.joined == true && !remote.pendingSync) {
                        confirmJoin(payload.activityId, activities)
                        Outcome.DONE
                    } else if (remote == null) {
                        Outcome.RETRY_LATER
                    } else {
                        clearPendingJoin(payload.activityId, activities)
                        Outcome.REJECTED
                    }
                }
                e.code() == 401 -> Outcome.RETRY_LATER
                e.code() in 400..499 -> {
                    clearPendingJoin(payload.activityId, activities)
                    Outcome.REJECTED
                }
                else -> Outcome.RETRY_LATER
            }

        } catch (e: IOException) {
            Outcome.RETRY_LATER

        } catch (e: Exception) {
            Outcome.RETRY_LATER
        }
    }

    /**
     * Reconstruye la petición de creación desde el payload JSON y la envía al backend.
     */
    private suspend fun sendCreate(action: PendingAction): Outcome {
        val request = try {
            Gson().fromJson(action.payload.toString(), CreateActivityRequest::class.java)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return Outcome.UNPROCESSABLE
        } ?: return Outcome.UNPROCESSABLE

        return try {
            val response = ApiClient.api.createActivity(request)

            when (response.code()) {
                201 -> {
                    ActivityChanges.notifyChanged()
                    Outcome.DONE
                }
                401, 403, 408, 429 -> Outcome.RETRY_LATER
                in 500..599 -> Outcome.RETRY_LATER
                in 400..499 -> Outcome.REJECTED
                else -> Outcome.RETRY_LATER
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Outcome.RETRY_LATER
        } catch (e: Exception) {
            Outcome.RETRY_LATER
        }
    }

    private suspend fun confirmJoin(id: String, activities: ActivityDao) {
        activities.getActivityById(id)?.let {
            val updated = it.copy(joined = true, pendingSync = false)
            activities.insertActivity(updated)
            ActivityMemoryCache.put(updated.toActivity())
        }
        ActivityRepository(activities).getActivityById(id)
        ActivityChanges.notifyChanged()
    }

    private suspend fun clearPendingJoin(id: String, activities: ActivityDao) {
        activities.getActivityById(id)?.let {
            val updated = it.copy(joined = false, pendingSync = false, participants = (it.participants - 1).coerceAtLeast(0))
            activities.insertActivity(updated)
            ActivityMemoryCache.put(updated.toActivity())
        }
        ActivityRepository(activities).getActivityById(id)
        ActivityChanges.notifyChanged()
    }

    /** Las cuatro cosas que pueden pasarle a una acción de la cola. */
    private enum class Outcome {
        DONE,
        REJECTED,
        RETRY_LATER,
        UNPROCESSABLE
    }
}