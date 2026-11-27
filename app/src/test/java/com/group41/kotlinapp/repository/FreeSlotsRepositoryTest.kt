package com.group41.kotlinapp.repository

import com.group41.kotlinapp.analytics.BreakDetector
import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.network.SyncResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime

/**
 * Pruebas de como se consiguen y se guardan los huecos del dia.
 *
 * Corren sin emulador porque el almacen entra por el constructor: la version real usa
 * MMKV, que necesita Android, y aqui se reemplaza por una de mentira en memoria. Eso es
 * lo que permite probar las reglas que de verdad importan, que son cuando se refresca y
 * que pasa cuando la red falla.
 */
class FreeSlotsRepositoryTest {

    /** Un almacen de mentira: guarda en una variable y cuenta las escrituras. */
    private class FakeStore(var stored: StoredSlots? = null) : SlotStore {
        var writes = 0
        override fun read(): StoredSlots? = stored
        override fun write(value: StoredSlots) {
            stored = value
            writes++
        }
    }

    /** Un horario de mentira: cuenta las llamadas y puede fallar a voluntad. */
    private class FakeSchedule(
        private val slots: List<FreeSlotDto> = emptyList(),
        private val failure: Exception? = null
    ) : ScheduleRepository {
        var calls = 0
        override fun googleConnected() = true
        override fun setGoogleConnected(connected: Boolean) = Unit
        override suspend fun connectGoogle(authCode: String): SyncResult = error("sin uso")
        override suspend fun resyncGoogle(): SyncResult = error("sin uso")
        override suspend fun freeSlots(date: String): List<FreeSlotDto> {
            calls++
            failure?.let { throw it }
            return slots
        }
    }

    private val hoy = "2026-11-26"

    private val huecoManana = FreeSlotDto(
        start = "2026-11-26T10:00:00-05:00",
        end = "2026-11-26T11:30:00-05:00"
    )

    private fun millis(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun laPrimeraVezPideLosHuecosAlServidorYLosGuarda() = runTest {
        val store = FakeStore()
        val schedule = FakeSchedule(slots = listOf(huecoManana))

        val repo = FreeSlotsRepository(
            schedule = schedule,
            store = store,
            today = { hoy }
        )

        assertEquals(listOf(huecoManana), repo.slotsForToday())
        assertEquals(1, schedule.calls)
        assertEquals(hoy, store.stored?.date)
    }

    @Test
    fun siYaEstanGuardadosDeHoyNoSeVuelveAPedirlos() = runTest {
        val store = FakeStore(StoredSlots(hoy, listOf(huecoManana)))
        val schedule = FakeSchedule()

        val repo = FreeSlotsRepository(
            schedule = schedule,
            store = store,
            today = { hoy }
        )

        repo.slotsForToday()
        repo.slotsForToday()
        repo.slotsForToday()

        // Esta es la razon de existir del cache. Sin el, mostrar diez cupones en pantalla
        // dispararia diez peticiones, una por cada evento que hay que marcar.
        assertEquals(0, schedule.calls)
    }

    @Test
    fun losHuecosDeAyerNoSeUsanYSePidenDeNuevo() = runTest {
        val store = FakeStore(StoredSlots("2026-11-25", listOf(huecoManana)))
        val schedule = FakeSchedule(slots = emptyList())

        val repo = FreeSlotsRepository(
            schedule = schedule,
            store = store,
            today = { hoy }
        )

        repo.slotsForToday()

        // La caducidad es la fecha, no un temporizador. Un TTL de doce horas serviria
        // huecos de ayer a las dos de la madrugada.
        assertEquals(1, schedule.calls)
        assertEquals(hoy, store.stored?.date)
    }

    @Test
    fun siLaRedFallaSeDevuelveDesconocidoYNoUnaListaVacia() = runTest {
        val store = FakeStore()
        val schedule = FakeSchedule(failure = IOException("sin red"))

        val repo = FreeSlotsRepository(
            schedule = schedule,
            store = store,
            today = { hoy },
            logWarning = { _, _ -> }
        )

        // null y no emptyList(): una lista vacia afirmaria que hoy no hay huecos, y lo
        // cierto es que no se sabe. El detector trata los dos casos al reves.
        assertNull(repo.slotsForToday())
        assertEquals(0, store.writes)
    }

    @Test
    fun siLaRedFallaElEstadoDelDescansoEsDesconocido() = runTest {
        val repo = FreeSlotsRepository(
            schedule = FakeSchedule(failure = IOException("sin red")),
            detector = BreakDetector(clock = { millis("2026-11-26T10:30:00-05:00") }),
            store = FakeStore(),
            today = { hoy },
            logWarning = { _, _ -> }
        )

        val estado = repo.currentBreakState()

        // Sin horario no se marca el evento como "fuera de descanso": eso contaminaria el
        // grupo de control de la BQ-11.
        assertNull(estado.duringBreak)
    }

    @Test
    fun conHuecosGuardadosElEstadoSeCalculaSinTocarLaRed() = runTest {
        val schedule = FakeSchedule()

        val repo = FreeSlotsRepository(
            schedule = schedule,
            detector = BreakDetector(clock = { millis("2026-11-26T10:30:00-05:00") }),
            store = FakeStore(StoredSlots(hoy, listOf(huecoManana))),
            today = { hoy }
        )

        val estado = repo.currentBreakState()

        assertEquals(true, estado.duringBreak)
        assertEquals(60, estado.minutesLeft)
        // Lo importante: esto funciona igual en modo avion.
        assertEquals(0, schedule.calls)
    }

    @Test
    fun unaRespuestaVaciaDelServidorSeGuardaYSignificaQueNoHayHuecos() = runTest {
        val store = FakeStore()

        val repo = FreeSlotsRepository(
            schedule = FakeSchedule(slots = emptyList()),
            detector = BreakDetector(clock = { millis("2026-11-26T10:30:00-05:00") }),
            store = store,
            today = { hoy }
        )

        val estado = repo.currentBreakState()

        // El servidor respondio: hoy no hay huecos. Eso si es "fuera de descanso", y se
        // guarda para no volver a preguntarlo en todo el dia.
        assertEquals(false, estado.duringBreak)
        assertTrue(store.stored?.slots?.isEmpty() == true)
    }
}
