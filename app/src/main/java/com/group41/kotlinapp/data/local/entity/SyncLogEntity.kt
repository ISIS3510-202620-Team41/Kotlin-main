package com.group41.kotlinapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lo que paso cada vez que se intento mandar al servidor una accion que estaba en la cola.
 * Vive en la tabla `sync_log`.
 *
 * ¿Por que hace falta?
 *
 * La cola de acciones pendientes (`pending_actions`) solo dice que falta por mandar. En
 * cuanto una accion sale bien, su fila se borra, y con ella desaparece toda constancia de
 * que existio. Eso deja dos preguntas sin respuesta posible:
 *
 * - "Me inscribi sin internet, ¿llego o no?" El usuario no tiene forma de comprobarlo.
 * - "¿Por que esta inscripcion se descarto?" Nadie sabria que el servidor contesto 404.
 *
 * Esta tabla es el registro permanente de esos intentos. La cola dice que falta; el log
 * dice que ha pasado. Juntas se pueden cruzar en una sola consulta, que es la ventaja de
 * tener las dos en la misma base relacional.
 */
@Entity(
    tableName = "sync_log",
    // Se consulta de dos maneras distintas, asi que hay dos indices: por accion, para
    // mostrar el historial de una inscripcion concreta, y por fecha, para la pantalla
    // que lista los ultimos intentos.
    indices = [Index(value = ["actionId"]), Index(value = ["attemptedAt"])]
)
data class SyncLogEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * El id de la accion de la cola que se intento mandar.
     *
     * No es una clave foranea a proposito: la fila de `pending_actions` se borra en cuanto
     * la accion se completa, y una clave foranea obligaria a borrar tambien este registro
     * o a impedir el borrado. Se quiere justo lo contrario: que el log sobreviva a la cola.
     */
    val actionId: String,

    /** De quien era la accion. */
    val userId: String,

    /** JOIN_ACTIVITY, CREATE_ACTIVITY, etc. */
    val actionType: String,

    /** Como termino el intento. Los valores posibles estan en [SyncOutcome]. */
    val outcome: String,

    /**
     * El codigo HTTP que contesto el servidor, si llego a contestar.
     *
     * Es nulo cuando no hubo respuesta: se cayo la red a mitad, o se agoto el tiempo de
     * espera. Esa distincion importa, porque "el servidor dijo que no" y "no se pudo ni
     * preguntar" llevan a decisiones opuestas: la primera se descarta, la segunda se
     * reintenta.
     */
    val httpCode: Int?,

    /** Cuando se intento. */
    val attemptedAt: Long
)

/**
 * Los finales posibles de un intento de sincronizacion.
 *
 * Son constantes y no un enum guardado directamente porque lo que se escribe en SQLite
 * es texto. Tenerlas aqui evita que una consulta busque "exito" mientras el codigo que
 * escribe guarda "ok".
 */
object SyncOutcome {
    /** Llego al servidor y quedo confirmado. */
    const val SUCCESS = "SUCCESS"

    /** Fallo, pero por algo temporal: sigue en la cola para reintentarse. */
    const val RETRY = "RETRY"

    /** El servidor lo rechazo de forma definitiva. Se saco de la cola. */
    const val REJECTED = "REJECTED"
}
