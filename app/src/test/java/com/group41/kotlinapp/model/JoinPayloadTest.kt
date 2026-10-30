package com.group41.kotlinapp.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Comprueba que los datos de una inscripcion sobreviven el viaje de ida y vuelta a la
 * base de datos.
 *
 * ¿Por que merece sus propios tests algo tan simple como convertir a texto y volver?
 *
 * Porque es justo donde se pierden los datos sin que nadie se entere. La inscripcion se
 * guarda como texto, se queda ahi horas, y cuando por fin vuelve el internet se lee otra
 * vez para mandarla. Si algo se estropeo al guardar, el error no aparece en el momento,
 * aparece despues, cuando ya nadie esta mirando y la inscripcion simplemente no llega.
 */
class JoinPayloadTest {

    @Test
    fun `conserva los tres datos al guardar y volver a leer`() {
        val original = JoinPayload(
            activityId = "act-1",
            recommendationId = "rec-9",
            freeTimeMinutes = 45
        )

        // Se pasa por texto a proposito, que es exactamente lo que hace la base de datos:
        // guardar el JSON como una cadena en la columna `payload`.
        val recuperado = JoinPayload.fromJson(JSONObject(original.toJson().toString()))

        assertEquals(original, recuperado)
    }

    @Test
    fun `una inscripcion sin recomendacion ni tiempo libre sigue siendo valida`() {
        // Es el caso de quien llega a la actividad buscando por cercania en vez de desde
        // la lista de recomendaciones: no hay recomendacion de la cual venga.
        val original = JoinPayload("act-2", recommendationId = null, freeTimeMinutes = null)

        val recuperado = JoinPayload.fromJson(JSONObject(original.toJson().toString()))

        assertEquals("act-2", recuperado?.activityId)
        assertNull(recuperado?.recommendationId)
        assertNull(recuperado?.freeTimeMinutes)
    }

    @Test
    fun `cero minutos libres no se confunde con no tener el dato`() {
        // Esta distincion importa de verdad: 0 significa "el usuario no tenia ningun hueco
        // libre", que es informacion real que el backend usa en sus metricas. null significa
        // "no se pudo calcular". Si al guardar los dos terminaran igual, las estadisticas
        // de tiempo libre quedarian falseadas.
        val conCero = JoinPayload("act-3", null, freeTimeMinutes = 0)
        val sinDato = JoinPayload("act-3", null, freeTimeMinutes = null)

        assertEquals(0, JoinPayload.fromJson(conCero.toJson())?.freeTimeMinutes)
        assertNull(JoinPayload.fromJson(sinDato.toJson())?.freeTimeMinutes)
    }

    @Test
    fun `se descarta lo que no trae id de actividad`() {
        // Sin saber a que actividad inscribirse no hay peticion posible. Devolver null deja
        // que quien lo lea tire esa fila, en vez de intentar un envio que iba a fallar y se
        // quedaria reintentando para siempre, bloqueando la cola entera.
        val roto = JSONObject().put("recommendationId", "rec-1")

        assertNull(JoinPayload.fromJson(roto))
    }

    @Test
    fun `un texto vacio como id tampoco pasa`() {
        // Una cadena vacia no es un identificador, aunque la clave exista en el JSON.
        val vacio = JSONObject().put("activityId", "")

        assertNull(JoinPayload.fromJson(vacio))
    }

    @Test
    fun `el tipo de accion desconocido no revienta`() {
        // Pasa cuando el telefono guardo una accion con una version vieja de la app y luego
        // se actualizo a una que ya no la conoce. Devolver null permite descartar esa fila
        // en vez de dejar la sincronizacion rota para siempre.
        assertNull(PendingActionType.fromStorage("BORRAR_CUENTA"))
        assertEquals(PendingActionType.JOIN_ACTIVITY, PendingActionType.fromStorage("JOIN_ACTIVITY"))
    }
}
