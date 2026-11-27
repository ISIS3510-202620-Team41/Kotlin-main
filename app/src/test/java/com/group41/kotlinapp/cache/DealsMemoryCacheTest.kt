package com.group41.kotlinapp.cache

import com.group41.kotlinapp.data.local.entity.DealEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pruebas del cache en memoria del catalogo de cupones.
 *
 * Corren en el computador, sin emulador: el cache no depende de nada de Android.
 */
class DealsMemoryCacheTest {

    @Before
    fun setUp() {
        // Es un object, o sea compartido por todo el proceso, asi que cada test empieza
        // vaciandolo. Sin esto el orden de ejecucion cambiaria los resultados, que es
        // exactamente el fallo que la cache de busquedas destapo en su momento.
        DealsMemoryCache.invalidateAll()
        DealsMemoryCache.stats.reset()
    }

    private fun deal(id: String, category: String = "CAFE", minutes: Int = 10) = DealEntity(
        id = id,
        merchant = "Comercio $id",
        title = "Descuento $id",
        description = null,
        discountPercent = 20,
        category = category,
        minutesNeeded = minutes,
        validUntil = null,
        redemptionCode = null,
        imageUrl = null,
        cachedAt = 0L
    )

    @Test
    fun loGuardadoSeRecuperaEnLaSiguienteConsulta() {
        val catalogo = listOf(deal("a"), deal("b"))

        DealsMemoryCache.put(deals = catalogo)

        assertEquals(catalogo, DealsMemoryCache.get())
    }

    @Test
    fun unCacheVacioNoDevuelveNada() {
        // null y no una lista vacia: "no lo tengo" es distinto de "no hay cupones". Quien
        // llama tiene que poder distinguir si le toca ir a buscar al disco.
        assertNull(DealsMemoryCache.get())
    }

    @Test
    fun cadaCategoriaSeGuardaPorSeparado() {
        DealsMemoryCache.put(category = "CAFE", deals = listOf(deal("a", "CAFE")))
        DealsMemoryCache.put(category = "COMIDA", deals = listOf(deal("b", "COMIDA")))

        assertEquals(1, DealsMemoryCache.get("CAFE")?.size)
        assertEquals("b", DealsMemoryCache.get("COMIDA")?.first()?.id)

        // Y la lista completa es otra entrada distinta: pedir "todos" no puede devolver
        // la ultima categoria que alguien consulto.
        assertNull(DealsMemoryCache.get())
    }

    @Test
    fun unaCategoriaLlamadaAllNoChocaConLaListaCompleta() {
        DealsMemoryCache.put(deals = listOf(deal("completo")))
        DealsMemoryCache.put(category = "all", deals = listOf(deal("categoria")))

        // El prefijo de la clave es lo que evita esta colision. Sin el, una categoria
        // llamada "all" serviria el catalogo entero, y costaria mucho encontrarlo.
        assertEquals("completo", DealsMemoryCache.get()?.first()?.id)
        assertEquals("categoria", DealsMemoryCache.get("all")?.first()?.id)
    }

    @Test
    fun invalidarTiraTodasLasListasDeUnaVez() {
        DealsMemoryCache.put(deals = listOf(deal("a")))
        DealsMemoryCache.put(category = "CAFE", deals = listOf(deal("b")))

        DealsMemoryCache.invalidateAll()

        // Se vacia entero y no por categoria porque un cupon redimido cambia de estado en
        // todas las listas donde aparecia, y desde el cache no se sabe cuales son.
        assertEquals(0, DealsMemoryCache.size())
        assertNull(DealsMemoryCache.get())
        assertNull(DealsMemoryCache.get("CAFE"))
    }

    @Test
    fun seMideLaTasaDeAcierto() {
        DealsMemoryCache.put(deals = listOf(deal("a")))

        DealsMemoryCache.get()          // acierto
        DealsMemoryCache.get()          // acierto
        DealsMemoryCache.get("CAFE")    // fallo

        val stats = DealsMemoryCache.stats.snapshot()

        assertEquals(2, stats.hits)
        assertEquals(1, stats.misses)
        assertEquals(66, stats.hitRatePercent)
    }

    // NOTA: aqui habia un test que comprobaba que DealsMemoryCache se apunta solo en
    // CacheRegistry. Se quito porque dependia del orden de ejecucion: el registro ocurre
    // una sola vez, en el init del object, y CacheRegistryTest vacia el registro en su
    // setUp. Si ese test corria antes, este fallaba sin que nada estuviera roto.
    //
    // No se pierde cobertura: CacheRegistryTest.cadaCacheSeApuntaSoloAlCrearse ya prueba
    // ese mecanismo, y lo hace sobre caches que el propio test crea, sin depender de un
    // object compartido por todo el proceso.
}
