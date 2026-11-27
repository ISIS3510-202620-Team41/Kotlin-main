package com.group41.kotlinapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.App
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.analytics.BreakState
import com.group41.kotlinapp.data.local.entity.DealEntity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.DealInteractionDto
import com.group41.kotlinapp.repository.DealRepository
import com.group41.kotlinapp.repository.FreeSlotsRepository
import com.group41.kotlinapp.repository.RedeemResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Todo lo que la pantalla de cupones necesita mostrar, en un solo objeto. */
data class DealsUiState(
    val loading: Boolean = false,
    val deals: List<DealEntity> = emptyList(),

    /** Las categorias que de verdad tienen cupones vigentes. */
    val categories: List<String> = emptyList(),

    /** La categoria elegida, o null para todas. */
    val selectedCategory: String? = null,

    /**
     * En que momento del dia esta el estudiante.
     *
     * Acompana a cada evento que se manda a analitica, y ademas decide que cupones se
     * muestran primero: los que alcanza a usar antes de su proxima clase.
     */
    val breakState: BreakState = BreakState.UNKNOWN,

    /** El catalogo salio del telefono, sin preguntarle al servidor. */
    val fromLocalData: Boolean = false,

    val message: String? = null
)

/**
 * La logica de la pantalla de cupones comerciales (CampusDeals).
 *
 *
 * EL MULTITHREADING DE ESTA PANTALLA, Y POR QUE ES ASI
 *
 * Al abrirse hacen falta dos cosas que no dependen una de la otra: el catalogo de cupones
 * y los huecos entre clases del estudiante. Pedirlas en fila significaria esperar la suma
 * de las dos; pedirlas a la vez, esperar solo la mas lenta.
 *
 * Por eso `load()` lanza dos `async` sobre [Dispatchers.IO] y espera a los dos. Las dos
 * son operaciones de espera (disco y red), que es para lo que `IO` existe. Despues, el
 * cruce entre ambas (decidir que cupones caben en el tiempo que queda y en que orden) es
 * aritmetica sobre listas, asi que corre en [Dispatchers.Default]. Y la publicacion del
 * estado vuelve al hilo principal, porque es lo unico que puede tocar la pantalla.
 *
 * No es paralelismo puesto para cumplir un requisito: es que son dos preguntas
 * independientes y esperarlas en serie seria mas lento sin ganar nada.
 */
class DealsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = (application as App).database

    private val dealRepository = DealRepository(dealDao = database.dealDao())
    private val freeSlots = FreeSlotsRepository()

    private val _state = MutableStateFlow(DealsUiState())
    val state: StateFlow<DealsUiState> = _state.asStateFlow()

    /**
     * El catalogo completo, ya ordenado, antes de aplicar el filtro de categoria.
     *
     * Se guarda aparte del estado porque el estado contiene la lista ya filtrada, que es
     * lo que la pantalla dibuja. Si al cambiar de categoria se filtrara sobre lo que hay
     * en el estado, se filtraria sobre un filtro: elegir "Comida" y luego "Cafe" no
     * devolveria nada, porque ya no quedarian cupones de cafe en la lista.
     */
    private var fullCatalog: List<DealEntity> = emptyList()

    /**
     * Carga la pantalla.
     *
     * El orden importa: primero se siembra el catalogo local si esta vacio, para que
     * siempre haya algo que mostrar aunque el backend no responda nunca.
     */
    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, message = null) }

        viewModelScope.launch {
            dealRepository.seedIfEmpty()

            // --- Las dos cosas a la vez, no una detras de otra ---
            val catalogJob = async(Dispatchers.IO) {
                // Se refresca desde el servidor si hay red. Devuelve si lo consiguio, que
                // es lo que permite avisar en pantalla de que los datos son del telefono.
                val refreshed = dealRepository.refresh()
                refreshed to dealRepository.catalog().first()
            }

            val breakJob = async(Dispatchers.IO) {
                freeSlots.currentBreakState()
            }

            val (refreshed, catalog) = catalogJob.await()
            val breakState = breakJob.await()

            // --- El cruce entre las dos, en el hilo de calculo ---
            val ordered = withContext(Dispatchers.Default) {
                orderForBreak(catalog, breakState)
            }

            val categories = withContext(Dispatchers.IO) {
                database.dealDao().countByCategory(nowIso()).map { it.category }
            }

            fullCatalog = ordered

            // --- Y de vuelta al hilo principal para publicar ---
            _state.update {
                it.copy(
                    loading = false,
                    deals = applyFilter(ordered, it.selectedCategory),
                    categories = categories,
                    breakState = breakState,
                    fromLocalData = !refreshed
                )
            }

            reportShown(ordered, breakState)
        }
    }

    /** El usuario toco un chip de categoria. */
    fun selectCategory(category: String?) {
        _state.update { current ->
            current.copy(
                selectedCategory = category,
                deals = applyFilter(fullCatalog, category)
            )
        }
    }

    /**
     * El usuario abrio el detalle de un cupon.
     *
     * Es el paso intermedio del embudo de la BQ-11: entre que se muestra y que se redime.
     */
    fun onDealOpened(deal: DealEntity) {
        val breakState = _state.value.breakState

        Analytics.track(
            "deal_opened",
            mapOf(
                "dealId" to deal.id,
                "merchant" to deal.merchant,
                "category" to deal.category,
                "discountPercent" to deal.discountPercent,
                "minutesNeeded" to deal.minutesNeeded
            ) + breakState.toAnalyticsFields()
        )

        viewModelScope.launch {
            dealRepository.reportInteractions(listOf(interaction(deal, "OPENED", breakState)))
        }
    }

    /**
     * El usuario redime un cupon.
     *
     * Funciona sin conexion: la redencion se marca en el telefono y queda pendiente de
     * confirmar. El mensaje distingue los dos casos, porque no es lo mismo tener el
     * codigo en la mano que tener la promesa de que llegara.
     */
    fun redeem(deal: DealEntity) {
        val breakState = _state.value.breakState

        viewModelScope.launch {
            val result = dealRepository.redeem(
                dealId = deal.id,
                duringBreak = breakState.duringBreak,
                breakMinutesLeft = breakState.minutesLeft
            )

            Analytics.track(
                "deal_redeemed",
                mapOf(
                    "dealId" to deal.id,
                    "merchant" to deal.merchant,
                    "category" to deal.category,
                    "discountPercent" to deal.discountPercent,
                    "minutesNeeded" to deal.minutesNeeded,
                    "redeemedOffline" to (result is RedeemResult.Queued)
                ) + breakState.toAnalyticsFields()
            )

            fullCatalog = orderForBreak(dealRepository.catalog().first(), breakState)

            _state.update {
                it.copy(
                    deals = applyFilter(fullCatalog, it.selectedCategory),
                    message = when (result) {
                        is RedeemResult.Synced ->
                            "Listo. Muestra el codigo ${result.redemptionCode} en el local."
                        RedeemResult.Queued ->
                            "Guardado sin conexion. El codigo llegara cuando vuelva el internet."
                    }
                )
            }
        }
    }

    fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    /**
     * Pone primero los cupones que el estudiante alcanza a usar.
     *
     * Es el cruce entre las dos cargas, y es lo que hace util saber que esta en un hueco:
     * no sirve de nada ofrecerle un almuerzo de 45 minutos a alguien que tiene quince
     * antes de su proxima clase. Los que no caben no se esconden, se bajan: puede que
     * quiera guardarlo para despues.
     */
    private fun orderForBreak(deals: List<DealEntity>, breakState: BreakState): List<DealEntity> =
        deals.sortedWith(
            compareByDescending<DealEntity> { breakState.fits(it.minutesNeeded) }
                .thenByDescending { it.discountPercent }
        )

    private fun applyFilter(deals: List<DealEntity>, category: String?): List<DealEntity> =
        if (category == null) deals else deals.filter { it.category == category }

    /**
     * Reporta en un solo lote que estos cupones se mostraron.
     *
     * Es el denominador de la BQ-11. Va en lote porque una lista de diez cupones son diez
     * eventos, y una peticion por evento serian diez peticiones cada vez que se abre la
     * pantalla.
     */
    private fun reportShown(deals: List<DealEntity>, breakState: BreakState) {
        if (deals.isEmpty()) return

        Analytics.track(
            "deals_shown",
            mapOf("count" to deals.size) + breakState.toAnalyticsFields()
        )

        viewModelScope.launch {
            dealRepository.reportInteractions(deals.map { interaction(it, "SHOWN", breakState) })
        }
    }

    private fun interaction(deal: DealEntity, type: String, breakState: BreakState) =
        DealInteractionDto(
            dealId = deal.id,
            type = type,
            duringBreak = breakState.duringBreak,
            breakMinutesLeft = breakState.minutesLeft
        )

    private fun nowIso(): String = java.time.OffsetDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
}
