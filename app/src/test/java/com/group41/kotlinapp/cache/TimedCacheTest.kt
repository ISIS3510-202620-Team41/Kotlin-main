package com.group41.kotlinapp.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pruebas del cache con caducidad.
 *
 * Corren en el computador, sin emulador, porque la clase no depende de nada de Android.
 *
 * El reloj se controla desde el test: `ahora` es una variable que el test mueve a mano, y
 * el cache la consulta en lugar de preguntarle la hora al sistema. Eso es lo que permite
 * probar un tiempo de vida de un minuto sin que el test tarde un minuto, y es la razon por
 * la que el reloj entra por el constructor.
 */
class TimedCacheTest {

    /** La hora que ve el cache. El test la adelanta cuando quiere. */
    private var ahora = 0L

    private fun cache(maxEntries: Int = 10, ttlMillis: Long = 1_000) =
        TimedCache<String, String>(
            maxEntries = maxEntries,
            ttlMillis = ttlMillis,
            clock = { ahora }
        )

    @Test
    fun loGuardadoSeDevuelveMientrasNoCaduque() {
        val cache = cache(ttlMillis = 1_000)

        cache.put("a", "valor")
        ahora += 999

        assertEquals("valor", cache.get("a"))
    }

    @Test
    fun loGuardadoDejaDeServirCuandoPasaSuTiempoDeVida() {
        val cache = cache(ttlMillis = 1_000)

        cache.put("a", "valor")
        ahora += 1_000

        // Justo al cumplirse el tiempo ya no sirve: la comparacion es >=, no >. Un dato que
        // vale exactamente un segundo ha dejado de valer en el segundo uno.
        assertNull(cache.get("a"))
    }

    @Test
    fun laEntradaCaducadaSeBorraAlConsultarla() {
        val cache = cache(ttlMillis = 1_000)

        cache.put("a", "valor")
        ahora += 2_000

        assertEquals(1, cache.size())
        cache.get("a")

        // Consultar limpia: asi no hace falta un hilo aparte recorriendo el cache en busca
        // de caducados.
        assertEquals(0, cache.size())
    }

    @Test
    fun caducarNoEsLoMismoQueExpulsar() {
        val cache = cache(maxEntries = 10, ttlMillis = 1_000)

        cache.put("a", "valor")
        ahora += 2_000
        cache.get("a")

        val stats = cache.stats.snapshot()

        // Es la distincion que justifica tener dos contadores: la entrada era la unica del
        // cache, con nueve huecos libres, asi que no la tiro la falta de sitio. Dejo de
        // servir por edad. Si los dos casos se contaran juntos, un cache con el tiempo de
        // vida mal puesto se diagnosticaria como un cache demasiado pequeño.
        assertEquals(1, stats.stale)
        assertEquals(0, stats.evictions)
        assertEquals(0, stats.misses)
    }

    @Test
    fun invalidarTiraLaEntradaSinEsperarAQueCaduque() {
        val cache = cache(ttlMillis = 60_000)

        cache.put("a", "valor")
        cache.invalidate("a")

        // Sin invalidacion, este valor seguiria sirviendose durante un minuto entero
        // aunque el dato real ya hubiera cambiado.
        assertNull(cache.get("a"))
    }

    @Test
    fun alPasarseDelTopeSeTiraLaEntradaMenosUsada() {
        val cache = cache(maxEntries = 2, ttlMillis = 60_000)

        cache.put("a", "1")
        cache.put("b", "2")

        // Consultar "a" la convierte en la mas reciente en uso. Esto es lo que diferencia
        // un LRU de una cola: sin esta consulta, la que se iria seria "a" por ser la mas
        // antigua; con ella, se va "b".
        assertEquals("1", cache.get("a"))

        cache.put("c", "3")

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun seCuentaLaExpulsionCuandoElCacheSeLlena() {
        val cache = cache(maxEntries = 1, ttlMillis = 60_000)

        cache.put("a", "1")
        cache.put("b", "2")

        assertEquals(1, cache.stats.snapshot().evictions)
    }

    @Test
    fun laTasaDeAciertoSeCalculaSobreLasConsultasReales() {
        val cache = cache(ttlMillis = 60_000)

        cache.put("a", "1")
        cache.get("a")   // acierto
        cache.get("a")   // acierto
        cache.get("z")   // fallo
        cache.get("y")   // fallo

        val stats = cache.stats.snapshot()

        assertEquals(2, stats.hits)
        assertEquals(2, stats.misses)
        assertEquals(4, stats.lookups)
        assertEquals(50, stats.hitRatePercent)
    }

    @Test
    fun unCacheQueNadieConsultoTieneCeroPorCientoYNoCien() {
        val cache = cache()

        // Dividir entre cero reventaria, pero devolver cien seria peor: diria que un cache
        // sin usar funciona perfectamente.
        assertEquals(0, cache.stats.snapshot().hitRatePercent)
        assertEquals(0, cache.stats.snapshot().lookups)
    }

    @Test
    fun vaciarloTiraTodoDeUnaVez() {
        val cache = cache(ttlMillis = 60_000)

        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun guardarDosVecesLaMismaClaveReiniciaSuTiempoDeVida() {
        val cache = cache(ttlMillis = 1_000)

        cache.put("a", "viejo")
        ahora += 900
        cache.put("a", "nuevo")
        ahora += 900

        // Han pasado 1800 ms desde el primer guardado, pero solo 900 desde el segundo. La
        // fecha que cuenta es la del valor que esta guardado ahora, no la de la clave.
        assertEquals("nuevo", cache.get("a"))
    }
}
