package com.group41.kotlinapp.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pruebas del listado de caches.
 *
 * Corren en el computador porque el listado usa `androidx.collection.ArrayMap`, que es Java
 * puro. Con `android.util.ArrayMap`, que es la del sistema, estos tests exigirian un
 * emulador. Fue una de las razones para elegir la de androidx.
 */
class CacheRegistryTest {

    @Before
    fun setUp() {
        // El listado es un object, o sea compartido por todo el proceso. Cada test empieza
        // vaciandolo para que el orden de ejecucion no cambie los resultados.
        CacheRegistry.clear()
    }

    @Test
    fun cadaCacheSeApuntaSoloAlCrearse() {
        TimedCache<String, String>(maxEntries = 5, ttlMillis = 1_000, name = "uno")
        TimedCache<String, String>(maxEntries = 5, ttlMillis = 1_000, name = "dos")

        // Nadie llamo a register: lo hace el bloque init de cada cache. Es lo que evita
        // tener una lista escrita a mano que se queda vieja.
        val nombres = CacheRegistry.snapshots().map { it.name }

        assertEquals(2, nombres.size)
        assertTrue(nombres.contains("uno"))
        assertTrue(nombres.contains("dos"))
    }

    @Test
    fun elListadoRefleljaLosContadoresDeCadaCache() {
        val cache = TimedCache<String, String>(maxEntries = 5, ttlMillis = 60_000, name = "uno")

        cache.put("a", "1")
        cache.get("a")   // acierto
        cache.get("z")   // fallo

        val snapshot = CacheRegistry.snapshots().single { it.name == "uno" }

        assertEquals(1, snapshot.hits)
        assertEquals(1, snapshot.misses)
        assertEquals(50, snapshot.hitRatePercent)
    }

    @Test
    fun registrarDosVecesElMismoNombreNoDuplicaLaEntrada() {
        val stats = CacheStats("uno")

        CacheRegistry.register("uno", stats)
        CacheRegistry.register("uno", stats)

        // Es el comportamiento de cualquier mapa, pero conviene dejarlo probado: si se
        // duplicara, la pantalla de ajustes mostraria el mismo cache dos veces.
        assertEquals(1, CacheRegistry.snapshots().size)
    }

    @Test
    fun reiniciarPoneACeroTodosLosCachesDeUnaVez() {
        val uno = TimedCache<String, String>(maxEntries = 5, ttlMillis = 60_000, name = "uno")
        val dos = TimedCache<String, String>(maxEntries = 5, ttlMillis = 60_000, name = "dos")

        uno.put("a", "1"); uno.get("a")
        dos.put("b", "2"); dos.get("b")

        CacheRegistry.resetAll()

        // Los contadores se van a cero, pero lo guardado sigue ahi: reiniciar las metricas
        // no es vaciar los caches. Son dos cosas distintas y el boton de los ajustes tiene
        // que poder hacer una sin la otra.
        assertTrue(CacheRegistry.snapshots().all { it.lookups == 0L })
        assertEquals("1", uno.get("a"))
    }
}
