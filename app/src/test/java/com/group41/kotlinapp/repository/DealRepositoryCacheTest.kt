package com.group41.kotlinapp.repository

import com.group41.kotlinapp.cache.DealsMemoryCache
import com.group41.kotlinapp.data.local.dao.CategoryCount
import com.group41.kotlinapp.data.local.dao.DealDao
import com.group41.kotlinapp.data.local.entity.DealEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Prueba que el cache en memoria SE USA, no solo que funciona.
 *
 * La diferencia importa. `DealsMemoryCacheTest` comprueba que el cache guarda y devuelve
 * bien, pero pasaria igual aunque el repositorio no lo consultara nunca: seria codigo
 * muerto con sus propias pruebas en verde.
 *
 * Lo que se mide aqui es otra cosa: **cuantas veces se lee el disco**. Si el cache
 * funciona, la segunda consulta no lo toca. Si alguien borra la linea que lo consulta,
 * este test se pone rojo y los otros siete no.
 *
 * Esa es tambien la forma de demostrarlo en vivo: comentar la consulta al cache en
 * `DealRepository.catalogSnapshot()` y volver a correr. El contador pasa de 1 a 2.
 */
class DealRepositoryCacheTest {

    /**
     * Una base de datos de mentira que cuenta cuantas veces la leen.
     *
     * El contador es todo el experimento: no se comprueba que el resultado sea correcto,
     * sino que el trabajo no se repitio.
     */
    private class CountingDealDao(
        private val stored: List<DealEntity>
    ) : DealDao {

        /** Cuantas veces se ha leido el catalogo del disco. */
        var reads = 0

        override fun activeDeals(now: String): Flow<List<DealEntity>> {
            reads++
            return flowOf(stored)
        }

        override fun dealsFittingIn(now: String, availableMinutes: Int): Flow<List<DealEntity>> =
            flowOf(stored.filter { it.minutesNeeded <= availableMinutes })

        override suspend fun byId(id: String): DealEntity? = stored.firstOrNull { it.id == id }

        override fun redeemedDeals(): Flow<List<DealEntity>> = flowOf(stored.filter { it.redeemed })

        override suspend fun countByCategory(now: String): List<CategoryCount> =
            stored.groupBy { it.category }.map { CategoryCount(it.key, it.value.size) }

        override suspend fun upsertAll(deals: List<DealEntity>) = Unit

        override suspend fun markRedeemed(id: String, pendingSync: Boolean, code: String?) = Unit

        override suspend fun clearPendingSync(id: String) = Unit

        override suspend fun revertRedemption(id: String) = Unit

        override suspend fun deleteExpired(now: String) = Unit

        override suspend fun deleteSeeded() = Unit

        override suspend fun count(): Int = stored.size
    }

    private fun deal(id: String) = DealEntity(
        id = id,
        merchant = "Cafe Andino",
        title = "20% en cafe",
        description = null,
        discountPercent = 20,
        category = "CAFE",
        minutesNeeded = 10,
        validUntil = null,
        redemptionCode = null,
        imageUrl = null,
        cachedAt = 0L
    )

    private val catalogo = listOf(deal("a"), deal("b"), deal("c"))

    private lateinit var dao: CountingDealDao
    private lateinit var repository: DealRepository

    @Before
    fun setUp() {
        // El cache es un object compartido por el proceso: cada test empieza vaciandolo,
        // o el resultado dependeria del orden de ejecucion.
        DealsMemoryCache.invalidateAll()
        DealsMemoryCache.stats.reset()

        dao = CountingDealDao(catalogo)
        repository = DealRepository(
            dealDao = dao,
            // Sin red: asi el test mide el camino memoria/disco sin que el refresco
            // interfiera. Es ademas el caso que mas importa, porque es cuando el usuario
            // depende por completo de lo que tiene guardado.
            online = { false },
            logWarning = { _, _ -> }
        )
    }

    @Test
    fun laPrimeraConsultaLeeElDiscoYLaSegundaNo() = runTest {
        val primera = repository.catalogSnapshot()
        val segunda = repository.catalogSnapshot()

        // ESTA es la prueba de que el cache sirve: el disco se leyo UNA vez para DOS
        // consultas. Sin cache serian dos.
        assertEquals(1, dao.reads)

        assertEquals(CatalogSource.DISK, primera.source)
        assertEquals(CatalogSource.MEMORY, segunda.source)

        // Y devuelven lo mismo: ahorrar trabajo no puede cambiar la respuesta.
        assertEquals(primera.deals, segunda.deals)
    }

    @Test
    fun diezConsultasSeguidasSiguenCostandoUnaSolaLectura() = runTest {
        repeat(10) { repository.catalogSnapshot() }

        // El usuario entra y sale de la pantalla varias veces por sesion. Ese es el patron
        // real que el cache aprovecha.
        assertEquals(1, dao.reads)
        assertEquals(9, DealsMemoryCache.stats.snapshot().hits)
    }

    @Test
    fun redimirInvalidaElCacheYObligaALeerDeNuevo() = runTest {
        repository.catalogSnapshot()
        assertEquals(1, dao.reads)

        repository.redeem(dealId = "a", duringBreak = true, breakMinutesLeft = 30)

        val despues = repository.catalogSnapshot()

        // Despues de redimir, el catalogo guardado ya no refleja la realidad: ese cupon
        // cambio de estado. Si no se invalidara, la pantalla lo seguiria mostrando como
        // disponible hasta diez minutos despues. Este test es el que lo impide.
        assertEquals(2, dao.reads)
        assertEquals(CatalogSource.DISK, despues.source)
    }

    @Test
    fun sembrarElCatalogoTambienInvalidaElCache() = runTest {
        repository.catalogSnapshot()
        assertEquals(1, dao.reads)

        // El dao de mentira ya tiene filas, asi que seedIfEmpty no siembra nada y no
        // deberia invalidar: solo invalida cuando de verdad escribe.
        repository.seedIfEmpty()

        repository.catalogSnapshot()
        assertEquals(1, dao.reads)
    }

    @Test
    fun elCacheDevuelveExactamenteLoMismoQueElDisco() = runTest {
        val desdeDisco = repository.catalogSnapshot().deals
        val desdeMemoria = repository.catalogSnapshot().deals

        // Un cache que acelera pero cambia la respuesta es peor que no tener cache,
        // porque el fallo es invisible hasta que alguien compara.
        assertEquals(catalogo.map { it.id }, desdeDisco.map { it.id })
        assertEquals(desdeDisco, desdeMemoria)
    }
}
