package com.group41.kotlinapp.analytics

import com.group41.kotlinapp.network.FreeSlotDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * Pruebas del detector de descansos, que es la base de la BQ-11.
 *
 * Corren en el computador, sin emulador, porque el detector no depende de nada de Android:
 * recibe los huecos ya cargados y la hora por el constructor.
 *
 * Lo que de verdad se prueba aqui no es "funciona el calculo", sino que la BQ no quede
 * sesgada. Si el detector mete a los usuarios sin horario en el grupo de control, o cuenta
 * como descanso un instante en que la clase ya empezo, los numeros de la sustentacion
 * estarian mal y nadie se enteraria.
 */
class BreakDetectorTest {

    /** La hora que ve el detector. Cada test la pone donde necesita. */
    private var ahora = 0L

    private val detector = BreakDetector(clock = { ahora })

    /** Convierte "2026-11-26T10:00:00-05:00" a milisegundos, para situar el reloj. */
    private fun millis(iso: String): Long =
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    /** Un hueco de 10:00 a 11:30, hora de Bogota. */
    private val huecoManana = FreeSlotDto(
        start = "2026-11-26T10:00:00-05:00",
        end = "2026-11-26T11:30:00-05:00"
    )

    /** Otro de 14:00 a 14:45. */
    private val huecoTarde = FreeSlotDto(
        start = "2026-11-26T14:00:00-05:00",
        end = "2026-11-26T14:45:00-05:00"
    )

    @Test
    fun dentroDeUnHuecoReportaDescansoYLosMinutosQueQuedan() {
        ahora = millis("2026-11-26T10:30:00-05:00")

        val estado = detector.evaluate(listOf(huecoManana, huecoTarde))

        assertEquals(true, estado.duringBreak)
        assertEquals(60, estado.minutesLeft)
    }

    @Test
    fun entreDosHuecosReportaQueNoEsDescanso() {
        // Las 12:00: el hueco de la manana termino y el de la tarde no ha empezado.
        ahora = millis("2026-11-26T12:00:00-05:00")

        val estado = detector.evaluate(listOf(huecoManana, huecoTarde))

        assertEquals(false, estado.duringBreak)
        assertNull(estado.minutesLeft)
    }

    @Test
    fun elInicioDelHuecoYaCuentaComoDescanso() {
        ahora = millis("2026-11-26T10:00:00-05:00")

        // El inicio es inclusivo: el primer milisegundo del hueco ya es descanso.
        assertEquals(true, detector.evaluate(listOf(huecoManana)).duringBreak)
    }

    @Test
    fun elFinalDelHuecoYaNoCuentaComoDescanso() {
        ahora = millis("2026-11-26T11:30:00-05:00")

        // El final es exclusivo. Si no lo fuera, un cupon mostrado en ese instante
        // contaria como "durante el descanso" cuando la clase ya empezo, y el borde
        // sumaria al grupo equivocado de la BQ.
        assertEquals(false, detector.evaluate(listOf(huecoManana)).duringBreak)
    }

    @Test
    fun sinHorarioElEstadoEsDesconocidoYNoFalso() {
        ahora = millis("2026-11-26T10:30:00-05:00")

        val estado = detector.evaluate(null)

        // Este es el test que protege la BQ. Si `duringBreak` fuera false, todos los
        // usuarios que no han importado su horario entrarian al grupo de control y
        // dilurian la tasa de redencion "fuera de descanso".
        assertNull(estado.duringBreak)
        assertNull(estado.minutesLeft)
    }

    @Test
    fun listaVaciaSiEsInformacionYSignificaFueraDeDescanso() {
        ahora = millis("2026-11-26T10:30:00-05:00")

        // Distinto de null: aqui el backend si respondio y dijo que hoy no hay huecos.
        val estado = detector.evaluate(emptyList())

        assertEquals(false, estado.duringBreak)
    }

    @Test
    fun unHuecoMalFormadoSeDescartaSinTumbarElResto() {
        ahora = millis("2026-11-26T14:20:00-05:00")

        val conBasura = listOf(
            FreeSlotDto(start = "no es una fecha", end = "tampoco"),
            huecoTarde
        )

        // El hueco valido sigue detectandose. Un error de formato en una fila no puede
        // dejar sin cupones al usuario.
        val estado = detector.evaluate(conBasura)
        assertEquals(true, estado.duringBreak)
        assertEquals(25, estado.minutesLeft)
    }

    @Test
    fun unHuecoQueTerminaAntesDeEmpezarSeDescarta() {
        ahora = millis("2026-11-26T10:30:00-05:00")

        val invertido = FreeSlotDto(
            start = "2026-11-26T11:00:00-05:00",
            end = "2026-11-26T09:00:00-05:00"
        )

        // Esta corrupto. Descartarlo explicitamente es mejor que dejarlo pasar, porque
        // asi no se confunde con un hueco que simplemente no contiene la hora actual.
        assertEquals(false, detector.evaluate(listOf(invertido)).duringBreak)
    }

    @Test
    fun elOffsetDeZonaHorariaSeRespeta() {
        // Las 10:30 en Bogota son las 15:30 UTC. Si el detector ignorara el offset y
        // comparara horas sueltas, este test fallaria.
        ahora = millis("2026-11-26T15:30:00Z")

        assertEquals(true, detector.evaluate(listOf(huecoManana)).duringBreak)
    }

    @Test
    fun unCuponCabeSiAlcanzaConLoQueQuedaDelHueco() {
        ahora = millis("2026-11-26T11:00:00-05:00")   // quedan 30 minutos

        val estado = detector.evaluate(listOf(huecoManana))

        assertTrue(estado.fits(minutesNeeded = 20))
        assertTrue(estado.fits(minutesNeeded = 30))
        assertFalse(estado.fits(minutesNeeded = 45))
    }

    @Test
    fun sinHorarioSeMuestranTodosLosCupones() {
        // Ante la duda se le ensena la oferta al usuario y que el decida. Esconderle un
        // cupon por una suposicion cuesta mas que mostrarle uno que no alcance a usar.
        assertTrue(BreakState.UNKNOWN.fits(minutesNeeded = 90))
    }

    @Test
    fun fueraDeDescansoNoCabeNingunCupon() {
        assertFalse(BreakState.OUTSIDE.fits(minutesNeeded = 5))
    }

    @Test
    fun losCamposDeAnaliticaSiempreLlevanLasMismasClaves() {
        ahora = millis("2026-11-26T10:30:00-05:00")

        val enDescanso = detector.evaluate(listOf(huecoManana)).toAnalyticsFields()
        val sinHorario = BreakState.UNKNOWN.toAnalyticsFields()

        // Las claves tienen que ser identicas en los tres estados: la consulta que cruza
        // los eventos mostrado/abierto/redimido agrupa por estas columnas, y una clave
        // ausente en unos eventos y presente en otros rompe el cruce en silencio.
        assertEquals(enDescanso.keys, sinHorario.keys)
        assertEquals(true, enDescanso["duringBreak"])
        assertEquals(60, enDescanso["breakMinutesLeft"])

        // El nulo se manda igual: "no se sabia" es un dato, y omitir la clave lo haria
        // indistinguible de un evento que perdio el campo.
        assertTrue(sinHorario.containsKey("duringBreak"))
        assertNull(sinHorario["duringBreak"])
    }
}
