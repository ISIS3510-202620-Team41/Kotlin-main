package com.group41.kotlinapp.repository

import com.google.gson.Gson
import com.group41.kotlinapp.data.local.dao.PendingActionDao
import com.group41.kotlinapp.data.local.entity.PendingActionEntity
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.model.PendingAction
import com.group41.kotlinapp.model.PendingActionType
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.CreateActivityRequest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val gson: Gson = Gson()
) {

    private val dao: PendingActionDao
        get() = pendingActionDao

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
     *
     * El orden importa: si mañana se encolan "crear actividad" y "unirse a esa actividad",
     * mandarlas al reves fallaria porque la actividad todavia no existiria en el servidor.
     * El DAO ya las entrega ordenadas por fecha.
     *
     * Las filas que no se pueden entender (un tipo de accion que esta version de la app ya
     * no conoce, o un JSON incompleto) se saltan en vez de romper la lista entera.
     */
    suspend fun pendingFor(userId: String): List<PendingAction> = withContext(Dispatchers.IO) {
        dao.getPendingActions(userId).mapNotNull { it.toDomainOrNull() }
    }

    /**
     * Saca la accion de la cola porque ya llego al backend.
     *
     * Se borra en vez de marcarla como "hecha" porque una accion cumplida no le sirve a
     * nadie: dejarla solo haria crecer la tabla sin motivo en el telefono del usuario.
     */
    suspend fun markSynced(action: PendingAction) = withContext(Dispatchers.IO) {
        dao.deleteAction(action.id, action.userId)
    }

    /**
     * Registra que el intento de mandar esta accion fallo.
     *
     * @return true si la accion sigue en la cola para reintentarse, false si se descarto.
     *
     * La logica de cuando rendirse: un fallo aislado casi siempre es temporal (el internet
     * volvio a medias, el servidor estaba reiniciandose), asi que vale la pena reintentar.
     * Pero reintentar para siempre tiene dos problemas: gasta bateria y datos del usuario,
     * y una accion rota atascada al frente de la cola impediria que salieran las de atras.
     *
     * Por eso se cuenta cuantas veces fallo y a las MAX_RETRIES se descarta.
     */
    suspend fun recordFailure(action: PendingAction): Boolean = withContext(Dispatchers.IO) {
        val attempts = action.retryCount + 1
        if (attempts >= MAX_RETRIES) {
            dao.deleteAction(action.id, action.userId)
            return@withContext false
        }
        dao.updateAction(
            PendingActionEntity(
                id = action.id,
                userId = action.userId,
                actionType = action.type.name,
                payload = action.payload.toString(),
                createdAt = action.createdAt,
                retryCount = attempts
            )
        )
        true
    }

    /**
     * Descarta la accion sin contar reintentos, porque reintentarla no va a servir de nada.
     *
     * Es distinto de recordFailure: aqui el backend respondio y dijo que no. Por ejemplo,
     * la actividad se lleno, se cancelo, o el usuario ya estaba inscrito. Mandarla otra vez
     * daria exactamente la misma respuesta, asi que insistir solo gastaria bateria.
     */
    suspend fun discard(action: PendingAction) = withContext(Dispatchers.IO) {
        dao.deleteAction(action.id, action.userId)
    }

    /**
     * Convierte una fila de la base de datos en algo que el resto del codigo pueda usar.
     *
     * Devuelve null cuando la fila no se puede interpretar. Quien llama la descarta.
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
        /**
         * Cuantos intentos fallidos se toleran antes de rendirse con una accion.
         *
         * Cinco es un numero conservador: con la espera creciente entre intentos que aplica
         * WorkManager (10 s, 20 s, 40 s...), cinco intentos cubren varios minutos de red
         * inestable. Mas que eso ya no es "la conexion esta mala", es que algo esta roto.
         */
        const val MAX_RETRIES = 5

        /**
         * El identificador de una inscripcion pendiente.
         *
         * No es aleatorio a proposito: se arma con el usuario y la actividad, asi que dos
         * toques en el mismo boton producen el MISMO id. Como el id es la clave primaria de
         * la tabla, la base de datos rechaza sola el segundo y no quedan dos inscripciones
         * encoladas para la misma actividad.
         *
         * Incluye el usuario porque dos personas pueden usar el mismo telefono: sus
         * inscripciones a una misma actividad son acciones distintas y no deben pisarse.
         */
        fun joinActionId(userId: String, activityId: String): String =
            "${PendingActionType.JOIN_ACTIVITY.name}:$userId:$activityId"
    }
}
