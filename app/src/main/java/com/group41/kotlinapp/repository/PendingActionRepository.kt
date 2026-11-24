package com.group41.kotlinapp.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.group41.kotlinapp.data.local.AppDatabase
import com.group41.kotlinapp.data.local.entity.UserEnrollmentEntity
import com.group41.kotlinapp.model.CancelEnrollmentPayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.group41.kotlinapp.data.local.dao.PendingActionDao
import com.group41.kotlinapp.data.local.entity.PendingActionEntity
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.model.PendingAction
import com.group41.kotlinapp.model.PendingActionType
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.CreateActivityRequest
import com.group41.kotlinapp.network.EnrollmentAccount
import java.util.UUID
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    private val gson: Gson = Gson(),
    private val database: AppDatabase? = null
) {

    // The Room constructor lets us update the enrollment and queue in one transaction.
    // The DAO-only constructor still supports activity creation and photo uploads.
    constructor(database: AppDatabase, apiServiceProvider: () -> ApiService = { ApiClient.api }) :
        this(database.pendingActionDao(), apiServiceProvider, database = database)

    val supportsEnrollments: Boolean get() = database != null

    suspend fun enrollment(userId: String, activityId: String): UserEnrollmentEntity? =
        database?.userEnrollmentDao()?.get(userId, activityId)

    suspend fun enrollmentPending(userId: String, activityId: String): Boolean =
        requireNotNull(database).userEnrollmentDao().hasPendingAction(
            userId, joinActionId(userId, activityId), cancellationActionId(userId, activityId)
        )

    // Save the server state only if the read is current and no local change is pending.
    // A delayed response could otherwise restore an enrollment the user just cancelled.
    suspend fun rememberMembership(
        userId: String,
        activityId: String,
        joined: Boolean,
        isCurrentRead: () -> Boolean
    ) = enrollmentMutex.withLock {
        if (!isCurrentRead()) return@withLock
        val db = requireNotNull(database)
        db.withTransaction {
            if (!enrollmentPending(userId, activityId)) {
                val enrollments = db.userEnrollmentDao()
                val previous = enrollments.get(userId, activityId)
                    ?: UserEnrollmentEntity(userId, activityId, joined = false)
                enrollments.upsert(previous.withConfirmedMembership(joined))
            }
        }
    }

    // The screen and WorkManager share this lock so the previous attempt commits
    // its result before another enrollment change is processed.
    suspend fun changeEnrollment(
        userId: String,
        payload: JoinPayload,
        joined: Boolean,
        online: Boolean,
        api: () -> ApiService,
        isCurrentUser: () -> Boolean,
        schedule: () -> Unit
    ): JoinResult = enrollmentMutex.withLock {
        check(isCurrentUser()) { "La sesion cambio" }
        val db = requireNotNull(database)
        val id = payload.activityId
        val type = if (joined) PendingActionType.JOIN_ACTIVITY else PendingActionType.CANCEL_ENROLLMENT
        val actionId = if (joined) joinActionId(userId, id) else cancellationActionId(userId, id)
        val oppositeId = if (joined) cancellationActionId(userId, id) else joinActionId(userId, id)
        val body = if (joined) payload.toJson() else CancelEnrollmentPayload(id).toJson()
        // Save the state shown to the user and the action to send in one transaction.
        // Both can then be restored together after an app restart.
        val action = withContext(NonCancellable) {
            db.withTransaction {
                val enrollments = db.userEnrollmentDao()
                val existing = enrollments.get(userId, id)
                    ?: UserEnrollmentEntity(userId, id, joined = false)
                // An opposing action replaces the previous request. Repeated requests
                // for the same change keep one row through insertIfAbsent.
                dao.deleteAction(oppositeId, userId)
                dao.insertIfAbsent(PendingActionEntity(
                    actionId, userId, type.name, body.toString(), System.currentTimeMillis(), 0
                ))
                enrollments.upsert(existing.copy(joined = joined, cancellationPending = !joined))
                checkNotNull(dao.getPendingActions(userId).first { it.id == actionId }.toDomainOrNull())
            }.also {
                // Schedule background sync in case the immediate attempt is interrupted.
                schedule()
                ActivityChanges.notifyChanged()
            }
        }
        if (!online) return@withLock JoinResult.QUEUED

        val failure = sendEnrollment(action, api, isCurrentUser)
        if (failure != null && !isTemporaryEnrollmentFailure(failure)) throw failure
        if (failure == null) JoinResult.SYNCED else JoinResult.QUEUED
    }

    /** Returns false if the server rejects the action and propagates retryable failures. */
    suspend fun syncEnrollment(action: PendingAction, isCurrentUser: () -> Boolean): Boolean =
        enrollmentMutex.withLock {
            // The queue may have changed while the worker waited for the lock.
            // Read the current row before sending it.
            val current = dao.getPendingActions(action.userId)
                .firstOrNull { it.id == action.id }?.toDomainOrNull()
                ?: return@withLock true
            val failure = sendEnrollment(current, apiServiceProvider, isCurrentUser)
            if (failure != null && isTemporaryEnrollmentFailure(failure)) {
                dao.updateAction(PendingActionEntity(
                    current.id, current.userId, current.type.name, current.payload.toString(),
                    current.createdAt, current.retryCount + 1
                ))
                throw failure
            }
            failure == null
        }

    private suspend fun sendEnrollment(
        action: PendingAction,
        api: () -> ApiService,
        isCurrentUser: () -> Boolean
    ): Exception? {
        val joining = action.type == PendingActionType.JOIN_ACTIVITY
        val join = if (joining) JoinPayload.fromJson(action.payload) else null
        val id = if (joining) join?.activityId else CancelEnrollmentPayload.fromJson(action.payload)?.activityId
        val db = requireNotNull(database)
        if (id == null) {
            dao.deleteAction(action.id, action.userId)
            return IllegalArgumentException("La accion no contiene una actividad")
        }
        if (!isCurrentUser()) return IOException("La sesion cambio")

        var confirmedAfterConflict: Boolean? = null
        var cancellationRemovedEnrollment = false
        val account = EnrollmentAccount(action.userId)
        val failure = try {
            if (join != null) {
                api().join(id, join.recommendationId, join.freeTimeMinutes, account)
            } else {
                val response = api().leave(id, account)
                // The server returns 404 when the enrollment is already absent.
                // This also covers a successful cancellation whose response was lost.
                if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
                cancellationRemovedEnrollment = response.isSuccessful
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A 409 can also mean the activity is full. Fetch its details to
            // distinguish that case from an already-confirmed enrollment.
            if (join != null && e is HttpException && e.code() == 409) {
                try {
                    if (!isCurrentUser()) throw IOException("La sesion cambio")
                    val detail = api().getActivity(id, account = account)
                    if (!detail.isSuccessful) throw HttpException(detail)
                    val remoteJoined = detail.body()?.get("joined")?.asBoolean
                        ?: throw IOException("El servidor no devolvio el estado de inscripcion")
                    confirmedAfterConflict = remoteJoined
                    if (remoteJoined) null else e
                } catch (verifyError: CancellationException) {
                    throw verifyError
                } catch (verifyError: Exception) {
                    IOException("No se pudo confirmar la inscripcion", verifyError)
                }
            } else if (e is HttpException || e is IOException) e else
                IOException("No se pudo confirmar el cambio de inscripcion", e)
        }

        // Commit the enrollment state and remove the queued action together once
        // the server gives a definitive answer. On rejection, restore the confirmed state.
        if (failure == null || !isTemporaryEnrollmentFailure(failure)) {
            withContext(NonCancellable) {
                db.withTransaction {
                    val enrollments = db.userEnrollmentDao()
                    val previous = enrollments.get(action.userId, id)
                        ?: UserEnrollmentEntity(action.userId, id, joined = !joining)
                    val updated = if (failure == null) {
                        previous.withConfirmedMembership(joining, cancellationRemovedEnrollment)
                    } else {
                        val confirmed = confirmedAfterConflict ?: previous.confirmedJoined
                        previous.copy(joined = confirmed, cancellationPending = false, confirmedJoined = confirmed)
                    }
                    enrollments.upsert(updated)
                    dao.deleteAction(action.id, action.userId)
                }
            }
        }
        ActivityChanges.notifyChanged()
        return failure
    }

    /**
     * Records confirmed membership transitions while retaining the latest cancellation.
     * A 404 after reversing an unsent join leaves no history because there is no evidence
     * that the user ever enrolled. A 404 for a known enrollment still confirms its removal.
     */
    private fun UserEnrollmentEntity.withConfirmedMembership(
        joined: Boolean,
        cancellationRemovedEnrollment: Boolean = false
    ): UserEnrollmentEntity {
        val now = System.currentTimeMillis()
        return copy(
            joined = joined,
            cancellationPending = false,
            confirmedJoined = joined,
            enrolledAt = if (joined && (!confirmedJoined || enrolledAt == null)) now else enrolledAt,
            canceledAt = if (!joined && (confirmedJoined || cancellationRemovedEnrollment)) now else canceledAt
        )
    }

    private fun isTemporaryEnrollmentFailure(error: Exception): Boolean =
        error is IOException || error is HttpException &&
            (error.code() in listOf(401, 408, 425, 429) || error.code() >= 500)

    private val dao: PendingActionDao
        get() = pendingActionDao

    /**
     * Queues an activity creation request in Room for eventual synchronization.
     * Generates a unique UUID for the action and requires a verified non-blank userId.
     *
     * @param userId The authenticated user's ID.
     * @param request The activity creation details to serialize and store.
     * @param photoPath Optional local photo path, stored only as queue metadata.
     * @return The unique generated action ID.
     */
    suspend fun queueActivityCreation(
        userId: String,
        request: CreateActivityRequest,
        photoPath: String? = null
    ): String {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        // Reject stale paths before saving a queue entry that could never upload its photo.
        require(photoPath == null || (File(photoPath).isFile && File(photoPath).length() > 0L)) {
            "Activity photo must be an existing, nonempty file"
        }

        // 1. Generate a unique action ID
        val actionId = UUID.randomUUID().toString()

        // Keep request fields at the root so existing queued actions remain compatible.
        val payload = gson.toJsonTree(request).asJsonObject
        if (!photoPath.isNullOrBlank()) {
            // This is local metadata, not a field of the Spring Boot request DTO.
            payload.addProperty(LOCAL_PHOTO_PATH, photoPath)
        }
        val payloadJson = gson.toJson(payload)

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
     * Creates activities, then retries their photos independently using the saved backend ID.
     * Removes a photo-bearing action only after upload and local file cleanup succeed.
     *
     * @param userId The authenticated user's ID.
     * @return The number of successfully synchronized actions.
     */
    suspend fun syncPendingActions(userId: String): Int {
        require(userId.isNotBlank()) { "User ID must not be blank" }

        val pendingActions = pendingFor(userId)
        var syncedCount = 0

        for (action in pendingActions) {
            when (action.type) {
                PendingActionType.CREATE_ACTIVITY, PendingActionType.UPLOAD_ACTIVITY_PHOTO -> {
                    try {
                        syncActivityCreation(action)
                        markSynced(action)
                        syncedCount++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordFailure(action)
                        if (e is HttpException && e.code() in listOf(401, 403)) break
                    }
                }
                else -> Unit
            }
        }

        return syncedCount
    }

    /** Shared by WorkManager and direct sync so neither can drop a pending photo. */
    suspend fun syncActivityCreation(queuedAction: PendingAction) = withContext(Dispatchers.IO) {
        // A caller may hold a snapshot from before a previous create-to-upload transition.
        val action = dao.getPendingActions(queuedAction.userId)
            .firstOrNull { it.id == queuedAction.id }?.toDomainOrNull() ?: return@withContext
        var upload = action
        if (action.type == PendingActionType.CREATE_ACTIVITY) {
            // Local metadata is ignored when rebuilding the unchanged Spring Boot DTO.
            val request = gson.fromJson(action.payload.toString(), CreateActivityRequest::class.java)
            val response = apiServiceProvider().createActivity(request)
            if (response.code() != 201) throw HttpException(response)
            val photoPath = action.payload.optString(LOCAL_PHOTO_PATH).takeIf { it.isNotBlank() }
            if (photoPath == null) {
                ActivityChanges.notifyChanged()
                return@withContext
            }

            val payload = JSONObject().put(LOCAL_PHOTO_PATH, photoPath)
            response.body()?.get("id")?.takeUnless { it.isJsonNull }?.let {
                payload.put(BACKEND_ACTIVITY_ID, it.asString)
            }
            upload = action.copy(type = PendingActionType.UPLOAD_ACTIVITY_PHOTO, payload = payload, retryCount = 0)
            // Replace the same Room row in one update BEFORE attempting the upload.
            // A failed upload or restart now retries only the photo, never POST /activities.
            // Finish this short local write even if WorkManager cancels after HTTP 201.
            withContext(NonCancellable) {
                dao.updateAction(PendingActionEntity(
                    id = upload.id, userId = upload.userId, actionType = upload.type.name,
                    payload = payload.toString(), createdAt = upload.createdAt, retryCount = 0
                ))
            }
            ActivityChanges.notifyChanged()
        }

        val activityId = upload.payload.optString(BACKEND_ACTIVITY_ID)
        // Keep a malformed 201 response pending rather than creating the activity again.
        check(activityId.isNotBlank()) { "Created activity response is missing its ID" }
        val file = File(upload.payload.getString(LOCAL_PHOTO_PATH))
        if (!upload.payload.optBoolean(PHOTO_UPLOADED)) {
            check(file.isFile && file.length() > 0L) { "Queued activity photo is missing or empty" }
            val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            val response = apiServiceProvider().uploadActivityImage(activityId, part)
            if (!response.isSuccessful) throw HttpException(response)

            // Persist success before cleanup, so a restart after deletion needs no JPEG.
            upload.payload.put(PHOTO_UPLOADED, true)
            withContext(NonCancellable) {
                dao.updateAction(PendingActionEntity(
                    id = upload.id, userId = upload.userId, actionType = upload.type.name,
                    payload = upload.payload.toString(), createdAt = upload.createdAt, retryCount = upload.retryCount
                ))
            }
        }

        // Only a successful upload permits deleting the JPEG. Retry cleanup if necessary.
        if (!file.delete() && file.exists()) throw IOException("Unable to delete uploaded activity photo")
        ActivityChanges.notifyChanged()
    }

    /**
     * Guarda una inscripcion que no se pudo mandar.
     *
     * @return true si quedo encolada, false si ya habia una identica esperando.
     */
    suspend fun enqueueJoin(
        userId: String,
        payload: JoinPayload
    ): Boolean = withContext(Dispatchers.IO) {
        val entity = PendingActionEntity(
            id = joinActionId(userId, payload.activityId),
            userId = userId,
            actionType = PendingActionType.JOIN_ACTIVITY.name,
            payload = payload.toJson().toString(),
            createdAt = System.currentTimeMillis(),
            retryCount = 0
        )
        // insertIfAbsent devuelve -1 cuando ya existia una fila con el mismo id, es decir,
        // cuando el usuario ya habia encolado esta misma inscripcion.
        dao.insertIfAbsent(entity) != -1L
    }

    /**
     * Devuelve todo lo que este usuario tiene pendiente, en el orden en que lo hizo.
     */
    suspend fun pendingFor(userId: String): List<PendingAction> = withContext(Dispatchers.IO) {
        dao.getPendingActions(userId).mapNotNull { it.toDomainOrNull() }
    }

    /**
     * Saca la accion de la cola porque ya llego al backend.
     */
    suspend fun markSynced(action: PendingAction) = withContext(Dispatchers.IO) {
        dao.deleteAction(action.id, action.userId)
    }

    /**
     * Registra que el intento de mandar esta accion fallo.
     *
     * @return true si la accion sigue en la cola para reintentarse, false si se descarto.
     *
     * Para creaciones (`CREATE_ACTIVITY`), se incrementa el contador de reintentos pero NUNCA
     * se descarta por alcanzar `MAX_RETRIES` ante fallos temporales de servidor o red,
     * previniendo que la actividad del usuario se pierda definitivamente.
     */
    suspend fun recordFailure(action: PendingAction): Boolean = withContext(Dispatchers.IO) {
        // Creation may already have become a photo upload during this attempt.
        // Read the durable row so a stale worker snapshot cannot turn it back into creation.
        val current = dao.getPendingActions(action.userId).firstOrNull { it.id == action.id }
            ?: return@withContext false
        val attempts = current.retryCount + 1

        // Las creaciones de actividad nunca se descartan por alcanzar el límite de reintentos
        if (current.actionType == PendingActionType.CREATE_ACTIVITY.name ||
            current.actionType == PendingActionType.UPLOAD_ACTIVITY_PHOTO.name) {
            dao.updateAction(current.copy(retryCount = attempts))
            return@withContext true
        }

        // Para otras acciones (ej. JOIN_ACTIVITY), eliminar si excede MAX_RETRIES
        if (attempts >= MAX_RETRIES) {
            dao.deleteAction(action.id, action.userId)
            return@withContext false
        }

        dao.updateAction(current.copy(retryCount = attempts))
        true
    }

    /**
     * Descarta la accion sin contar reintentos, porque reintentarla no va a servir de nada.
     */
    suspend fun discard(action: PendingAction) = withContext(Dispatchers.IO) {
        dao.deleteAction(action.id, action.userId)
    }

    /**
     * Convierte una fila de la base de datos en algo que el resto del codigo pueda usar.
     */
    private fun PendingActionEntity.toDomainOrNull(): PendingAction? {
        val type = PendingActionType.fromStorage(actionType) ?: return null
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return PendingAction(
            id = id,
            userId = userId,
            type = type,
            payload = json,
            createdAt = createdAt,
            retryCount = retryCount
        )
    }

    companion object {
        // The worker and screens create separate repositories for the same database,
        // so the lock must be shared across instances.
        private val enrollmentMutex = Mutex()

        private fun cancellationActionId(userId: String, activityId: String): String =
            "${PendingActionType.CANCEL_ENROLLMENT.name}:$userId:$activityId"

        const val MAX_RETRIES = 5
        const val LOCAL_PHOTO_PATH = "localPhotoPath"
        const val BACKEND_ACTIVITY_ID = "backendActivityId"
        private const val PHOTO_UPLOADED = "photoUploaded"

        fun joinActionId(userId: String, activityId: String): String =
            "${PendingActionType.JOIN_ACTIVITY.name}:$userId:$activityId"
    }
}
