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
 *
 * Hoy solo esta la inscripcion, que es la que pide este issue (Vista 4). Esta como enum y
 * no como texto suelto para que agregar la creacion de actividades mas adelante sea
 * agregar un valor aqui, y para que el compilador avise si en algun `when` se olvida
 * de manejar el caso nuevo.
 */
enum class PendingActionType {
    /** El usuario toco "Unirme" en una actividad sin tener internet. */
    JOIN_ACTIVITY;

    companion object {
        /**
         * Convierte el texto guardado en la base de datos al valor del enum.
         *
         * Devuelve null si el texto no corresponde a ningun tipo conocido. Eso puede pasar
         * si una version vieja de la app guardo una accion que la version actual ya no
         * entiende. Es mejor devolver null y descartar esa fila que reventar la
         * sincronizacion entera por una accion que ya no sabemos ejecutar.
         */
        fun fromStorage(value: String): PendingActionType? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * Los datos de una inscripcion pendiente.
 *
 * Se guardan como texto JSON dentro de la columna `payload` en vez de como columnas
 * propias de la tabla. ¿Por que?
 *
 * Porque la tabla `pending_actions` tiene que servir para TODAS las acciones: hoy la
 * inscripcion, mañana la creacion de una actividad, que necesita titulo, fecha, foto...
 * Si cada tipo tuviera sus columnas, la tabla crceria sin control y la mayoria de las
 * columnas estarian vacias en la mayoria de las filas.
 *
 * Guardando el JSON, la tabla queda igual de simple sin importar cuantos tipos haya, y
 * cada tipo decide que necesita. El precio es que no se puede filtrar por esos campos
 * con SQL, pero aqui no hace falta: la cola se lee entera y se procesa en orden.
 */
data class JoinPayload(
    val activityId: String,

    /**
     * De que carga de recomendaciones salio la actividad. Puede ser null si el usuario
     * llego a ella por otro camino, por ejemplo buscando por cercania.
     *
     * Se guarda porque el backend lo usa para medir que tan buenas son las recomendaciones
     * (es lo que alimenta las metricas del motor de analitica). Si se perdiera al encolar,
     * las inscripciones hechas sin internet quedarian fuera de esas metricas y los numeros
     * saldrian sesgados hacia los usuarios con buena conexion.
     */
    val recommendationId: String?,

    /** Cuanto tiempo libre tenia el usuario en ese momento. Null si no se pudo calcular. */
    val freeTimeMinutes: Int?
) {
    /** Convierte estos datos al texto JSON que se guarda en la columna `payload`. */
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ACTIVITY_ID, activityId)
        // `put` con null guardaria la palabra "null" como texto; putOpt simplemente no
        // escribe la clave cuando el valor no existe, que es lo que queremos.
        putOpt(KEY_RECOMMENDATION_ID, recommendationId)
        putOpt(KEY_FREE_TIME_MINUTES, freeTimeMinutes)
    }

    companion object {
        private const val KEY_ACTIVITY_ID = "activityId"
        private const val KEY_RECOMMENDATION_ID = "recommendationId"
        private const val KEY_FREE_TIME_MINUTES = "freeTimeMinutes"

        /**
         * Lee el JSON guardado y reconstruye los datos.
         *
         * Devuelve null si falta el id de la actividad, porque sin el no hay nada que
         * mandar al backend. Mejor descartar esa fila que intentar una peticion que
         * seguro va a fallar y se va a quedar reintentando para siempre.
         */
        fun fromJson(json: JSONObject): JoinPayload? {
            val activityId = json.optString(KEY_ACTIVITY_ID).takeIf { it.isNotBlank() }
                ?: return null
            return JoinPayload(
                activityId = activityId,
                // optString devuelve "" cuando la clave no esta; se traduce a null para que
                // el backend reciba "sin recomendacion" en vez de una cadena vacia.
                recommendationId = json.optString(KEY_RECOMMENDATION_ID).takeIf { it.isNotBlank() },
                // -1 como centinela: no existe un tiempo libre negativo, asi que sirve para
                // distinguir "no venia el dato" de "el dato era 0".
                freeTimeMinutes = json.optInt(KEY_FREE_TIME_MINUTES, -1).takeIf { it >= 0 }
            )
        }
    }
}
