package com.group41.kotlinapp.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pruebas del cache de busquedas por cercania.
 *
 * Lo que se prueba aqui no es el mecanismo de caducidad, que ya tiene sus propias pruebas,
 * sino la decision que hace que este cache sirva de algo: el redondeo de la clave. Sin el,
 * el cache seria codigo muerto, porque el sensor de ubicacion nunca devuelve dos veces
 * exactamente la misma coordenada y ninguna busqueda coincidiria con otra.
 */
class NearbySearchCacheTest {

    @Before
    fun setUp() {
        // Es un object, o sea un unico ejemplar para todo el proceso, asi que el estado
        // sobrevive de un test al siguiente. Sin este vaciado, el orden en que corren los
        // tests cambiaria sus resultados.
        NearbySearchCache.invalidateAll()
        NearbySearchCache.stats.reset()
    }

    @Test
    fun loGuardadoSeRecuperaConLaMismaBusqueda() {
        NearbySearchCache.put(4.65, -74.05, 2.0, listOf("act-1", "act-2"))

        assertEquals(listOf("act-1", "act-2"), NearbySearchCache.get(4.65, -74.05, 2.0))
    }

    @Test
    fun unaVariacionMinimaDelSensorAciertaIgual() {
        NearbySearchCache.put(4.650000, -74.050000, 2.0, listOf("act-1"))

        // Esta es la prueba que justifica el redondeo. El telefono quieto sobre la mesa
        // reporta coordenadas que cambian en el quinto decimal. Sin redondear, esta
        // consulta fallaria y el cache no acertaria nunca.
        assertEquals(listOf("act-1"), NearbySearchCache.get(4.6500004, -74.0499996, 2.0))
    }

    @Test
    fun moverseDeVerdadNoReusaElResultado() {
        NearbySearchCache.put(4.650, -74.050, 2.0, listOf("act-1"))

        // Un cambio en el tercer decimal son unos cien metros: ya es otro punto de partida,
        // y el redondeo no debe taparlo. Si lo tapara, el cache daria respuestas de otro
        // sitio, que es peor que no tener cache.
        assertNull(NearbySearchCache.get(4.655, -74.050, 2.0))
    }

    @Test
    fun elRadioFormaParteDeLaClave() {
        NearbySearchCache.put(4.65, -74.05, 2.0, listOf("act-1"))

        // Mismo sitio, otro radio: es otra pregunta y tiene otra respuesta. Si el radio no
        // estuviera en la clave, pedir cinco kilometros devolveria el resultado de dos.
        assertNull(NearbySearchCache.get(4.65, -74.05, 5.0))
    }

    @Test
    fun inscribirseInvalidaLasBusquedasGuardadas() {
        NearbySearchCache.put(4.65, -74.05, 2.0, listOf("act-1"))
        NearbySearchCache.put(4.70, -74.10, 3.0, listOf("act-2"))

        NearbySearchCache.invalidateAll()

        // Se vacian las dos, no solo la que contenia la actividad afectada: desde el cache
        // no se sabe en que busquedas aparecia. Con veinte entradas como tope, vaciarlo
        // entero cuesta menos que llevar esa cuenta.
        assertEquals(0, NearbySearchCache.size())
        assertNull(NearbySearchCache.get(4.65, -74.05, 2.0))
        assertNull(NearbySearchCache.get(4.70, -74.10, 3.0))
    }

    @Test
    fun seMideLaTasaDeAcierto() {
        NearbySearchCache.put(4.65, -74.05, 2.0, listOf("act-1"))

        NearbySearchCache.get(4.65, -74.05, 2.0)    // acierto
        NearbySearchCache.get(9.99, -70.00, 2.0)    // fallo

        val stats = NearbySearchCache.stats.snapshot()

        assertEquals(1, stats.hits)
        assertEquals(1, stats.misses)
        assertEquals(50, stats.hitRatePercent)
    }
}
