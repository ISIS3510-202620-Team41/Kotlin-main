package com.group41.kotlinapp.repository

import com.group41.kotlinapp.cache.DealsMemoryCache
import com.group41.kotlinapp.data.local.dao.CategoryCount
import com.group41.kotlinapp.data.local.dao.DealDao
import com.group41.kotlinapp.data.local.entity.DealEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pruebas de lo que pasa al redimir un cupon sin conexion.
 *
 * Corren sin emulador porque el DAO y la comprobacion de red entran por el constructor.
 */
class OfflineDealRedemptionTest {

    /** Un DAO de mentira que recuerda lo que le pidieron. */
    private class RecordingDealDao(private val stored: List<DealEntity>) : DealDao {

        /** Las llamadas a markRedeemed, con su bandera de pendiente y su codigo. */
        val redemptions = mutableListOf<Triple<String, Boolean, String?>>()

        /** Las redenciones revertidas tras un rechazo del servidor. */
        val reverted = mutableListOf<String>()

        override fun activeDeals(now: String): Flow<List<DealEntity>> = flowOf(stored)

        override fun dealsFittingIn(now: String, availableMinutes: Int): Flow<List<DealEntity>> =
            flowOf(stored)

        override suspend fun byId(id: String): DealEntity? = stored.firstOrNull { it.id == id }

        override fun redeemedDeals(): Flow<List<DealEntity>> = flowOf(emptyList())

        override suspend fun countByCategory(now: String): List<CategoryCount> = emptyList()

        override suspend fun upsertAll(deals: List<DealEntity>) = Unit

        override suspend fun markRedeemed(id: String, pendingSync: Boolean, code: String?) {
            redemptions += Triple(id, pendingSync, code)
        }

        override suspend fun clearPendingSync(id: String) = Unit

        override suspend fun revertRedemption(id: String) {
            reverted += id
        }

        override suspend fun deleteExpired(now: String) = Unit

        override suspend fun deleteSeeded() = Unit

        override suspend fun count(): Int = stored.size
    }

    private val cupon = DealEntity(
        id = "deal-7",
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

    private lateinit var dao: RecordingDealDao
    private lateinit var repository: DealRepository

    @Before
    fun setUp() {
        DealsMemoryCache.invalidateAll()
        dao = RecordingDealDao(listOf(cupon))
        repository = DealRepository(
            dealDao = dao,
            online = { false },
            logWarning = { _, _ -> }
        )
    }

    @Test
    fun sinConexionLaRedencionQuedaEncoladaYNoSeIntentaLaPeticion() = runTest {
        val resultado = repository.redeem("deal-7", duringBreak = true, breakMinutesLeft = 30)

        // El usuario recibe respuesta al instante. No espera al tiempo de espera de la
        // red para que le digan que no hay internet.
        assertEquals(RedeemResult.Queued, resultado)
    }

    @Test
    fun elCuponSeMarcaComoRedimidoYPendienteDeConfirmar() = runTest {
        repository.redeem("deal-7", duringBreak = true, breakMinutesLeft = 30)

        val (id, pendiente, codigo) = dao.redemptions.single()

        assertEquals("deal-7", id)

        // pendingSync = true es lo que hace que el boton salga en ambar y no en verde. La
        // actualizacion es optimista, pero el aviso la mantiene honesta.
        assertTrue(pendiente)

        // Y sin codigo: el codigo lo genera el servidor. Inventarlo aqui le daria al
        // usuario algo que ensenar en la caja y que el comercio no reconoceria.
        assertNull(codigo)
    }

    @Test
    fun redimirInvalidaElCatalogoGuardadoEnMemoria() = runTest {
        // Se llena el cache como lo dejaria una consulta anterior.
        DealsMemoryCache.put(deals = listOf(cupon))

        repository.redeem("deal-7", duringBreak = null, breakMinutesLeft = null)

        // Sin esta invalidacion, volver atras mostraria el cupon como disponible hasta
        // diez minutos despues de haberlo redimido.
        assertNull(DealsMemoryCache.get())
    }

    @Test
    fun redimirDosVecesElMismoCuponProduceLaMismaClaveDeCola() {
        val primera = PendingActionRepository.redeemDealActionId("u42", "deal-7")
        val segunda = PendingActionRepository.redeemDealActionId("u42", "deal-7")

        // El id se deriva del usuario y del cupon, no es aleatorio. Es lo que hace que
        // tocar "Redimir" cinco veces sin conexion deje una sola fila en la cola: las
        // cinco producen la misma clave primaria y la base descarta las repetidas.
        assertEquals(primera, segunda)
        assertEquals("REDEEM_DEAL:u42:deal-7", primera)
    }

    @Test
    fun cuponesDistintosDelMismoUsuarioNoSePisan() {
        val uno = PendingActionRepository.redeemDealActionId("u42", "deal-7")
        val otro = PendingActionRepository.redeemDealActionId("u42", "deal-9")

        assertTrue(uno != otro)
    }

    @Test
    fun usuariosDistintosPuedenRedimirElMismoCupon() {
        val ana = PendingActionRepository.redeemDealActionId("u42", "deal-7")
        val luis = PendingActionRepository.redeemDealActionId("u99", "deal-7")

        // Dos estudiantes en el mismo telefono, o el mismo cupon en dos cuentas: son dos
        // redenciones distintas y las dos tienen que llegar.
        assertTrue(ana != luis)
    }
}
