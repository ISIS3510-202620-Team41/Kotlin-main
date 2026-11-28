package com.group41.kotlinapp.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pruebas del payload con el que una redencion viaja en la cola.
 *
 * Lo que de verdad se protege aqui es la BQ-11. El payload cruza la frontera de la base
 * de datos convertido en texto, y si el contexto del descanso se corrompe al ida y vuelta,
 * la pregunta se responde con datos equivocados y nada avisa: la app funcionaria igual.
 */
class RedeemDealPayloadTest {

    @Test
    fun elPayloadSobreviveIdaYVuelta() {
        val original = RedeemDealPayload(
            dealId = "deal-7",
            duringBreak = true,
            breakMinutesLeft = 25
        )

        val recuperado = RedeemDealPayload.fromJson(JSONObject(original.toJson().toString()))

        assertEquals(original, recuperado)
    }

    @Test
    fun elDesconocidoSeGuardaComoNuloYNoComoFalso() {
        val sinHorario = RedeemDealPayload(
            dealId = "deal-7",
            duringBreak = null,
            breakMinutesLeft = null
        )

        val recuperado = RedeemDealPayload.fromJson(JSONObject(sinHorario.toJson().toString()))

        // Este es el test que protege la BQ-11. Si el nulo volviera como false, el
        // estudiante sin horario importado entraria al grupo de "fuera de descanso" y la
        // tasa de redencion de ese grupo saldria diluida.
        assertNull(recuperado?.duringBreak)
        assertNull(recuperado?.breakMinutesLeft)
    }

    @Test
    fun fueraDeDescansoSeDistingueDeDesconocido() {
        val fuera = RedeemDealPayload("deal-7", duringBreak = false, breakMinutesLeft = null)

        val recuperado = RedeemDealPayload.fromJson(JSONObject(fuera.toJson().toString()))

        // false y null son dos cosas distintas y las dos tienen que sobrevivir: "se sabe
        // que no estaba en un descanso" frente a "no se sabe".
        assertEquals(false, recuperado?.duringBreak)
        assertNull(recuperado?.breakMinutesLeft)
    }

    @Test
    fun unPayloadSinCuponSeDescarta() {
        val corrupto = JSONObject().put("duringBreak", true)

        // Sin id de cupon no hay nada que redimir. Devolver null hace que el worker lo
        // marque como imposible de procesar y lo saque de la cola, en vez de reintentarlo
        // para siempre.
        assertNull(RedeemDealPayload.fromJson(corrupto))
    }

    @Test
    fun losMinutosRestantesSeConservan() {
        val payload = RedeemDealPayload("deal-7", duringBreak = true, breakMinutesLeft = 0)

        val recuperado = RedeemDealPayload.fromJson(JSONObject(payload.toJson().toString()))

        // Cero minutos es un valor valido, no una ausencia: el estudiante redimio justo
        // cuando se le acababa el hueco. Si se tratara como nulo se perderia el caso mas
        // interesante de la BQ.
        assertEquals(0, recuperado?.breakMinutesLeft)
    }
}
