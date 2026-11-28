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

    /** The user requested to leave an activity. */
    CANCEL_ENROLLMENT,

    /** El usuario creo una actividad sin tener internet. */
    CREATE_ACTIVITY,

    /** The activity already exists; retry only its JPEG upload. */
    UPLOAD_ACTIVITY_PHOTO,

    /** El usuario redimio un cupon comercial sin tener internet. */
    REDEEM_DEAL;

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

/**
 * The payload replayed by the worker when cancelling an enrollment.
 * The queue row already identifies the user, so the payload stores only the activity ID.
 */
data class CancelEnrollmentPayload(val activityId: String) {
    fun toJson(): JSONObject = JSONObject().put("activityId", activityId)

    companion object {
        fun fromJson(json: JSONObject): CancelEnrollmentPayload? =
            json.optString("activityId").takeIf { it.isNotBlank() }?.let(::CancelEnrollmentPayload)
    }
}


/**
 * Lo que hace falta para redimir un cupon cuando vuelva la conexion.
 *
 * Lleva el contexto del descanso ademas del cupon, y esa es la razon de que exista en vez
 * de guardar solo el id. El estudiante pudo redimir dentro de un hueco entre clases y que
 * la accion se envie tres horas despues; si el servidor calculara ese contexto con la
 * hora de llegada, registraria el descanso equivocado y la BQ-11 contaria mal.
 */
data class RedeemDealPayload(
    val dealId: String,
    val duringBreak: Boolean?,
    val breakMinutesLeft: Int?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_DEAL_ID, dealId)
        // Los nulos se guardan como JSONObject.NULL y no se omiten: "no se sabia si era
        // un descanso" es un dato, y una clave ausente no se distingue de una perdida.
        put(KEY_DURING_BREAK, duringBreak ?: JSONObject.NULL)
        put(KEY_BREAK_MINUTES, breakMinutesLeft ?: JSONObject.NULL)
    }

    companion object {
        private const val KEY_DEAL_ID = "dealId"
        private const val KEY_DURING_BREAK = "duringBreak"
        private const val KEY_BREAK_MINUTES = "breakMinutesLeft"

        fun fromJson(json: JSONObject): RedeemDealPayload? {
            val dealId = json.optString(KEY_DEAL_ID).takeIf { it.isNotBlank() } ?: return null
            return RedeemDealPayload(
                dealId = dealId,
                duringBreak = if (json.isNull(KEY_DURING_BREAK)) null
                else json.optBoolean(KEY_DURING_BREAK),
                breakMinutesLeft = if (json.isNull(KEY_BREAK_MINUTES)) null
                else json.optInt(KEY_BREAK_MINUTES)
            )
        }
    }
}
