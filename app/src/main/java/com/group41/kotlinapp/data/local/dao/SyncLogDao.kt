package com.group41.kotlinapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.group41.kotlinapp.data.local.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Las consultas sobre el registro de intentos de sincronizacion.
 */
@Dao
interface SyncLogDao {

    @Insert
    suspend fun insert(entry: SyncLogEntity): Long

    /**
     * Los ultimos intentos del usuario, del mas reciente al mas viejo.
     *
     * Es lo que alimenta la pantalla donde el usuario puede ver que se mando y que fallo.
     */
    @Query(
        """
        SELECT * FROM sync_log
        WHERE userId = :userId
        ORDER BY attemptedAt DESC
        LIMIT :limit
        """
    )
    fun recentEntries(userId: String, limit: Int = 50): Flow<List<SyncLogEntity>>

    /**
     * Todo lo que se intento para una accion concreta.
     *
     * Responde la pregunta "¿esta inscripcion llego?" con el historial completo: cuantas
     * veces se intento, cuanto se tardo y con que contesto el servidor cada vez.
     */
    @Query("SELECT * FROM sync_log WHERE actionId = :actionId ORDER BY attemptedAt ASC")
    suspend fun historyFor(actionId: String): List<SyncLogEntity>

    /**
     * Cuantos intentos hubo de cada tipo de final.
     *
     * Un GROUP BY de una linea que da la salud de todo el mecanismo sin conexion: si
     * `RETRY` crece mucho mas que `SUCCESS`, algo esta mal en el reenvio.
     */
    @Query(
        """
        SELECT outcome, COUNT(*) AS total
        FROM sync_log
        WHERE userId = :userId
        GROUP BY outcome
        """
    )
    suspend fun outcomeTotals(userId: String): List<OutcomeTotal>

    /**
     * Las acciones que acabaron rechazadas, cruzando el log con la cola.
     *
     * Este LEFT JOIN es el motivo de tener las dos tablas en la misma base. Une cada
     * rechazo con su fila de la cola si todavia existe, lo que permite distinguir dos
     * situaciones que de otro modo se verian iguales:
     *
     * - La fila ya no esta en la cola: el rechazo se proceso bien y se limpio.
     * - La fila sigue en la cola pese al rechazo: hay un error, porque algo rechazado
     *   deberia haberse sacado de la cola.
     *
     * `payload` es nulo precisamente en el primer caso, que es el normal.
     */
    @Query(
        """
        SELECT
            log.actionId   AS actionId,
            log.actionType AS actionType,
            log.httpCode   AS httpCode,
            log.attemptedAt AS attemptedAt,
            queue.payload  AS payload
        FROM sync_log AS log
        LEFT JOIN pending_actions AS queue ON queue.id = log.actionId
        WHERE log.userId = :userId AND log.outcome = 'REJECTED'
        ORDER BY log.attemptedAt DESC
        """
    )
    suspend fun rejectedWithQueueState(userId: String): List<RejectedAction>

    /**
     * Borra las entradas mas viejas que la fecha dada.
     *
     * El log es informacion util, pero no indefinidamente: a nadie le importa un intento
     * fallido de hace tres meses. Se llama al arrancar la app con un limite de 30 dias.
     */
    @Query("DELETE FROM sync_log WHERE attemptedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("DELETE FROM sync_log WHERE userId = :userId")
    suspend fun clearFor(userId: String)
}

/** Cuantos intentos acabaron en un final concreto. */
data class OutcomeTotal(
    val outcome: String,
    val total: Int
)

/**
 * Una accion rechazada por el servidor, y si todavia quedo algo de ella en la cola.
 *
 * `payload` viene del LEFT JOIN: es nulo cuando la fila de la cola ya se limpio, que es
 * lo que deberia pasar siempre.
 */
data class RejectedAction(
    val actionId: String,
    val actionType: String,
    val httpCode: Int?,
    val attemptedAt: Long,
    val payload: String?
)
