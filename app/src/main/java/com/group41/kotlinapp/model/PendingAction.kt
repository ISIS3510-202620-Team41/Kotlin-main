package com.group41.kotlinapp.model

import org.json.JSONObject

/**
 * Una accion que el usuario hizo en la app pero que todavia no llego al backend.
 *
 * La idea de fondo: cuando no hay internet, en vez de mostrar un error y perder lo que el
 * usuario hizo, se guarda la accion en el telefono como si fuera una carta en el buzon.
 * Mas tarde, cuando vuelva la conexion, alguien pasa a recoger el buzon y la manda.
 *
 * Esto es lo que el issue llama "cola offline": una lista de cosas por mandar, guardada en
 * la base de datos local (tabla `pending_actions`) para que sobreviva aunque el usuario
 * cierre la app o apague el telefono.
 */
data class PendingAction(
    /** Identificador de la fila en la tabla local. */
    val id: String,

    /** De quien es la accion. La cola es de cada usuario; ver PendingActionRepository. */
    val userId: String,

    /** Que quiso hacer el usuario. */
    val type: PendingActionType,

    /** Los datos que hacen falta para repetir la accion contra el backend. */
    val payload: JSONObject,

    /** Cuando la hizo. Sirve para reenviarlas en el mismo orden en que ocurrieron. */
    val createdAt: Long,

    /** Cuantas veces se intento mandar y fallo. Ver PendingActionRepository.MAX_RETRIES. */
    val retryCount: Int
)

/**
 * Los tipos de accion que la app sabe guardar y reenviar.
 */
enum class PendingActionType {
    /** El usuario toco "Unirme" en una actividad sin tener internet. */
    JOIN_ACTIVITY,

    /** El usuario creo una actividad sin tener internet. */
    CREATE_ACTIVITY,

    /** The activity already exists; retry only its JPEG upload. */
    UPLOAD_ACTIVITY_PHOTO;

    companion object {
        /**
         * Convierte el texto guardado en la base de datos al valor del enum.
         */
        fun fromStorage(value: String): PendingActionType? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * Los datos de una inscripcion pendiente.
 */
data class JoinPayload(
    val activityId: String,
    val recommendationId: String?,
    val freeTimeMinutes: Int?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ACTIVITY_ID, activityId)
        putOpt(KEY_RECOMMENDATION_ID, recommendationId)
        putOpt(KEY_FREE_TIME_MINUTES, freeTimeMinutes)
    }

    companion object {
        private const val KEY_ACTIVITY_ID = "activityId"
        private const val KEY_RECOMMENDATION_ID = "recommendationId"
        private const val KEY_FREE_TIME_MINUTES = "freeTimeMinutes"

        fun fromJson(json: JSONObject): JoinPayload? {
            val activityId = json.optString(KEY_ACTIVITY_ID).takeIf { it.isNotBlank() }
                ?: return null
            return JoinPayload(
                activityId = activityId,
                recommendationId = json.optString(KEY_RECOMMENDATION_ID).takeIf { it.isNotBlank() },
                freeTimeMinutes = json.optInt(KEY_FREE_TIME_MINUTES, -1).takeIf { it >= 0 }
            )
        }
    }
}
