package com.group41.kotlinapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.group41.kotlinapp.data.local.entity.PendingActionEntity

/**
 * Data Access Object (DAO) for managing the offline queue in the 'pending_actions' table.
 * Provides durable local storage to queue user actions performed while offline.
 *
 * This line below declares the DAO contract
 */
@Dao
interface PendingActionDao {

    /**
     * Retrieves all pending actions queued for a specific user ID, ordered chronologically
     * by creation timestamp (createdAt ASC) with a tie-breaker by id ASC.
     *
     * Secondary sorting on `id` guarantees deterministic query results if two actions share
     * the exact same millisecond timestamp. Actual execution order requires the synchronization worker
     * to process items sequentially.
     */
    @Query("SELECT * FROM pending_actions WHERE userId = :userId ORDER BY createdAt ASC, id ASC")
    suspend fun getPendingActions(userId: String): List<PendingActionEntity>

    /**
     * Persists a new offline action into the queue.
     *
     * Uses Room's default conflict strategy (ABORT) to throw an exception on primary key collision,
     * preventing duplicate identifiers from silently overwriting queued operations.
     */
    @Insert
    suspend fun insertAction(action: PendingActionEntity)

    /**
     * Guarda la accion solo si no habia ya una igual, y avisa si la guardo o no.
     *
     * Existe aparte de `insertAction` porque esa usa la estrategia ABORT a proposito:
     * lanza excepcion si el id se repite. Eso esta bien cuando el id es nuevo cada vez,
     * pero la cola offline necesita lo contrario.
     *
     * El caso real: el usuario sin internet toca "Unirme", no ve respuesta inmediata y
     * vuelve a tocar. Son dos toques pero una sola intencion. Si se encolaran las dos,
     * al volver la conexion se mandarian dos inscripciones a la misma actividad.
     *
     * La solucion es darle a la accion un id que se calcula a partir de lo que hace
     * (ver PendingActionRepository.joinActionId) y dejar que la base de datos rechace
     * el repetido. IGNORE hace justo eso sin lanzar excepcion.
     *
     * Devuelve la posicion de la fila insertada, o -1 si ya existia una igual y no se
     * guardo nada. Quien llama usa ese -1 para saber que no hace falta encolar de nuevo.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(action: PendingActionEntity): Long

    /**
     * Updates an existing action in the queue.
     * Used when deliberately modifying a queued action, such as incrementing its retry count.
     */
    @Update
    suspend fun updateAction(action: PendingActionEntity)

    /**
     * Removes a single action from the queue once it has been successfully synchronized.
     * Scoped to both `id` and `userId` to ensure actions are only deleted from the owning account's queue.
     */
    @Query("DELETE FROM pending_actions WHERE id = :id AND userId = :userId")
    suspend fun deleteAction(id: String, userId: String)

    /**
     * Removes all pending actions belonging to a specific user ID.
     *
     * Architectural Warning: Avoid invoking this indiscriminately during account logout,
     * as doing so would silently discard user changes that have not yet synced with the server.
     */
    @Query("DELETE FROM pending_actions WHERE userId = :userId")
    suspend fun clearUserActions(userId: String)
}