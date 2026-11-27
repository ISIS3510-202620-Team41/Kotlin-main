package com.group41.kotlinapp.analytics

import com.group41.kotlinapp.network.FreeSlotDto
import java.time.OffsetDateTime

/**
 * Decide si el estudiante esta ahora mismo en un hueco entre clases.
 *
 * Para que sirve: es la mitad dificil de la BQ-11, que pregunta si los cupones comerciales
 * se interactuan y se redimen mas durante los descansos que fuera de ellos. Esa pregunta
 * solo se puede responder si cada evento que se manda a analitica viene marcado con el
 * contexto de "esto paso durante un descanso" o "esto paso en clase". Sin la marca, los
 * eventos cuentan cuantos cupones se redimen, pero no permiten compararlo contra nada, y
 * la BQ pide una tasa, no un conteo.
 *
 * Los huecos los calcula el backend en `GET api/schedules/me/gaps`, a partir del horario
 * que el estudiante importo. Esta clase no los pide ni los guarda: solo los interpreta.
 * Esa separacion es a proposito, y es lo que permite probarla en el computador sin red.
 *
 * **El reloj entra por el constructor** en vez de llamar a `System.currentTimeMillis()`.
 * Es el mismo motivo que en TimedCache: sin eso, probar "que pasa justo cuando termina el
 * descanso" exigiria esperar a que termine un descanso de verdad. Con el reloj inyectado,
 * el test se para donde quiera y corre en microsegundos.
 */
class BreakDetector(
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Evalua el momento actual contra los huecos del dia.
     *
     * @param slots los huecos libres, o null si no se conocen (el estudiante no ha
     *        importado su horario, o no se pudieron cargar).
     */
    fun evaluate(slots: List<FreeSlotDto>?): BreakState {
        // Sin horario no se sabe, y no saber no es lo mismo que estar en clase. Devolver
        // false aqui meteria a todos los usuarios sin horario en el grupo "fuera de
        // descanso", que es justo el grupo contra el que se compara: la BQ saldria sesgada
        // sin que nada lo avisara.
        if (slots == null) return BreakState.UNKNOWN

        val now = clock()

        // Una lista vacia si es informacion: el backend respondio y dijo que hoy no hay
        // huecos. Eso es "fuera de descanso" con certeza.
        val currentSlot = slots.firstNotNullOfOrNull { slot ->
            val range = parseRange(slot) ?: return@firstNotNullOfOrNull null
            if (now >= range.first && now < range.second) range else null
        } ?: return BreakState.OUTSIDE

        // El final es exclusivo: en el milisegundo en que el hueco termina, ya se esta
        // fuera. De otro modo un cupon mostrado en ese instante contaria como "durante el
        // descanso" cuando la clase ya empezo.
        val minutesLeft = ((currentSlot.second - now) / 60_000).toInt()

        return BreakState(duringBreak = true, minutesLeft = minutesLeft)
    }

    /**
     * Convierte un hueco a milisegundos, o null si viene mal formado.
     *
     * Las fechas llegan en ISO 8601 con offset, por ejemplo "2026-10-02T06:00:00-05:00".
     * El offset importa y por eso se usa OffsetDateTime y no una hora suelta: el backend
     * responde en la zona horaria del usuario, y un hueco de las 6 de la manana en Bogota
     * no es el mismo instante que uno de las 6 en otra zona.
     *
     * Un hueco invalido se descarta en lugar de lanzar. Un error de formato en una fila no
     * puede tumbar la pantalla de cupones, que es lo que el usuario vino a ver.
     */
    private fun parseRange(slot: FreeSlotDto): Pair<Long, Long>? = runCatching {
        val start = OffsetDateTime.parse(slot.start).toInstant().toEpochMilli()
        val end = OffsetDateTime.parse(slot.end).toInstant().toEpochMilli()

        // Un hueco que termina antes de empezar esta corrupto. Dejarlo pasar haria que
        // `now >= start && now < end` nunca se cumpliera, asi que seria invisible en vez
        // de ser un error: mejor descartarlo explicitamente.
        if (end <= start) null else start to end
    }.getOrNull()
}

/**
 * En que momento del dia esta el estudiante, desde el punto de vista de la BQ-11.
 *
 * `duringBreak` es nullable a proposito. Son tres estados y no dos:
 *
 * - `true`  : esta en un hueco, y `minutesLeft` dice cuanto le queda.
 * - `false` : se sabe su horario y ahora mismo no esta en un hueco.
 * - `null`  : no se sabe. No hay horario importado, o no se pudo cargar.
 *
 * Mezclar el tercero con el segundo es el error facil de cometer y el que arruinaria la
 * BQ: los usuarios sin horario pasarian a engrosar el grupo de control y la tasa de
 * redencion "fuera de descanso" quedaria diluida.
 */
data class BreakState(
    val duringBreak: Boolean?,
    /** Minutos que quedan del hueco. Null si no se esta en uno, o si no se sabe. */
    val minutesLeft: Int?
) {

    /**
     * Los campos que acompanan a cada evento de analitica.
     *
     * Se construyen aqui y no en cada sitio que manda un evento para que los tres eventos
     * de la BQ-11 (mostrado, abierto, redimido) lleven exactamente las mismas claves. Si
     * uno escribiera `during_break` y otro `duringBreak`, la consulta que cruza los tres
     * no cuadraria y el fallo seria invisible hasta el dia de la sustentacion.
     *
     * Los nulos se incluyen igual: que un evento no sepa si fue en descanso tambien es un
     * dato, y omitir la clave haria imposible distinguir "no se sabia" de "se perdio".
     */
    fun toAnalyticsFields(): Map<String, Any?> = mapOf(
        "duringBreak" to duringBreak,
        "breakMinutesLeft" to minutesLeft
    )

    /**
     * Si un cupon que toma [minutesNeeded] minutos cabe en lo que queda del hueco.
     *
     * Sin horario se responde que si. Es deliberado: ante la duda se le muestra la oferta
     * al usuario y que el decida, en vez de esconderle cupones por una suposicion. El
     * costo de un falso positivo es que vea un cupon que no alcanza a usar; el de un falso
     * negativo es no enterarse de una oferta que si le servia.
     */
    fun fits(minutesNeeded: Int): Boolean = when (duringBreak) {
        null -> true
        false -> false
        true -> (minutesLeft ?: 0) >= minutesNeeded
    }

    companion object {
        /** No se conoce el horario del estudiante. */
        val UNKNOWN = BreakState(duringBreak = null, minutesLeft = null)

        /** Se conoce el horario y ahora mismo no hay hueco. */
        val OUTSIDE = BreakState(duringBreak = false, minutesLeft = null)
    }
}
