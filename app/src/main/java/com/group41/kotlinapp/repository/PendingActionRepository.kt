package com.group41.kotlinapp.repository

import com.google.gson.Gson
import com.group41.kotlinapp.data.local.dao.PendingActionDao
import com.group41.kotlinapp.data.local.entity.PendingActionEntity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.CreateActivityRequest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository responsible for queuing, synchronizing, and managing offline actions in Room.
 *
 * @param pendingActionDao Mandatory DAO for persisting pending offline actions.
 * @param apiServiceProvider Provider function returning the ApiService instance on demand.
 * @param gson Utility for serializing and deserializing action payloads to/from JSON format.
 */
class PendingActionRepository(
    private val pendingActionDao: PendingActionDao,
    private val apiServiceProvider: () -> ApiService = { ApiClient.api },
    private val gson: Gson = Gson()
) {

    /**
     * Queues an activity creation request in Room for eventual synchronization.
     * Generates a unique UUID for the action and requires a verified non-blank userId.
     *
     * @param userId The authenticated user's ID.
     * @param request The activity creation details to serialize and store.
     * @return The unique generated action ID.
     */
    suspend fun queueActivityCreation(
        userId: String,
        request: CreateActivityRequest
    ): String {
        require(userId.isNotBlank()) { "User ID must not be blank" }

        // 1. Generate a unique action ID
        val actionId = UUID.randomUUID().toString()

        // 2. Convert the request into JSON
        val payloadJson = gson.toJson(request)

        // 3. Create a PendingActionEntity with the required fields
        val pendingAction = PendingActionEntity(
            id = actionId,
            userId = userId,
            actionType = "CREATE_ACTIVITY",
            payload = payloadJson,
            createdAt = System.currentTimeMillis(),
            retryCount = 0
        )

        // 4. Insert the entity using pendingActionDao.insertAction(...)
        pendingActionDao.insertAction(pendingAction)

        // 5. Return the generated action ID
        return actionId
    }

    /**
     * Synchronizes queued offline actions for the authenticated user with the backend server.
     *
     * Processes 'CREATE_ACTIVITY' actions by deserializing their JSON payload and submitting them via ApiService.
     * Removes queued actions from Room only after confirmed HTTP 201 backend success.
     *
     * @param userId The authenticated user's ID.
     * @return The number of successfully synchronized actions.
     */
    suspend fun syncPendingActions(userId: String): Int {
        require(userId.isNotBlank()) { "User ID must not be blank" }

        val pendingActions = pendingActionDao.getPendingActions(userId)
        var syncedCount = 0

        for (action in pendingActions) {
            when (action.actionType) {
                "CREATE_ACTIVITY" -> {
                    try {
                        val request = gson.fromJson(action.payload, CreateActivityRequest::class.java)
                        val response = apiServiceProvider().createActivity(request)

                        if (response.code() == 201) {
                            pendingActionDao.deleteAction(action.id, userId)
                            syncedCount++
                        } else {
                            pendingActionDao.updateAction(
                                action.copy(retryCount = action.retryCount + 1)
                            )
                            if (response.code() == 401 || response.code() == 403) {
                                break
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        pendingActionDao.updateAction(
                            action.copy(retryCount = action.retryCount + 1)
                        )
                    }
                }
            }
        }

        return syncedCount
    }
}