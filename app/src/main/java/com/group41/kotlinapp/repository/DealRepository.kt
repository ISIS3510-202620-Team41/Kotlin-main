package com.group41.kotlinapp.repository

import android.util.Log
import com.group41.kotlinapp.cache.DealsMemoryCache
import com.group41.kotlinapp.data.local.dao.DealDao
import com.group41.kotlinapp.data.local.entity.DealEntity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.DealDto
import com.group41.kotlinapp.network.DealInteractionBatch
import com.group41.kotlinapp.network.DealInteractionDto
import com.group41.kotlinapp.model.RedeemDealPayload
import com.group41.kotlinapp.network.RedemptionDto
import com.group41.kotlinapp.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * El catalogo de cupones comerciales.
 *
 * La pantalla nunca lee de la red: lee de la base local, siempre. El repositorio se
 * encarga aparte de refrescar esa base cuando hay conexion.
 *
 * Esa inversion es lo que hace que la pantalla funcione igual con internet y sin el, y
 * ademas que se dibuje al instante: no hay un estado de "cargando" esperando al servidor,
 * porque lo que se muestra ya estaba en el telefono. Cuando la respuesta del servidor
 * llega, el `Flow` de Room emite otra vez y la lista se actualiza sola.
 */
class DealRepository(
    private val dealDao: DealDao,
    /**
     * La cola de acciones pendientes.
     *
     * Es nullable porque los tests que solo miran el catalogo no la necesitan. Cuando
     * falta, una redencion sin conexion se marca en el telefono pero no se encola: se
     * avisa en el log en vez de fallar en silencio.
     */
    private val pendingActions: PendingActionRepository? = null,
    private val apiProvider: () -> ApiService = { ApiClient.api },
    /**
     * Si hay conexion, como funcion.
     *
     * Mismo motivo que en el resto del proyecto: `ApiClient.isOnline()` necesita el
     * contexto de Android, y detras de una funcion estas reglas se pueden probar en el
     * computador sin emulador.
     */
    private val online: () -> Boolean = { ApiClient.isOnline() },
    /**
     * Quien es el usuario de la sesion, como funcion.
     *
     * Mismo motivo que `online`: `ApiClient.tokens` es un `lateinit` que solo existe
     * dentro de la app, asi que leerlo directamente haria que cualquier prueba de
     * escritorio que pase por aqui reviente con "lateinit property tokens has not been
     * initialized".
     */
    private val currentUserId: () -> String? = { ApiClient.tokens.userId },
    private val now: () -> Long = System::currentTimeMillis,
    private val logWarning: (String, Throwable) -> Unit = { message, error ->
        Log.w("DealRepository", message, error)
    }
) {

    /**
     * El catalogo vigente, desde la base local.
     *
     * Devuelve un `Flow`, asi que la pantalla se suscribe una vez y recibe la lista nueva
     * cada vez que el catalogo cambia, venga de un refresco o de una redencion. Sin eso,
     * la pantalla tendria que volver a preguntar despues de cada accion y habria momentos
     * en que muestra datos viejos.
     */
    fun catalog(): Flow<List<DealEntity>> = dealDao.activeDeals(nowIso())

    /** Los cupones que caben en el tiempo que le queda al estudiante de su hueco. */
    fun catalogFittingIn(availableMinutes: Int): Flow<List<DealEntity>> =
        dealDao.dealsFittingIn(nowIso(), availableMinutes)

    /** Los que ya redimio. Es su historial, y funciona sin conexion. */
    fun redeemed(): Flow<List<DealEntity>> = dealDao.redeemedDeals()

    /**
     * El catalogo para mostrarlo una vez, pasando por los tres niveles.
     *
     * LOS TRES NIVELES Y POR QUE EXISTEN
     *
     * 1. **Memoria** ([DealsMemoryCache]): microsegundos. Es donde cae la mayoria de las
     *    consultas, porque el usuario entra y sale de la pantalla varias veces por sesion.
     * 2. **Room**: milisegundos. Sobrevive al cierre de la app, asi que es lo que hace que
     *    el catalogo este ahi al abrirla sin internet.
     * 3. **Red**: cientos de milisegundos, y solo si hay conexion. La hace [refresh].
     *
     * Cada nivel existe porque el siguiente es ordenes de magnitud mas lento. Sin el
     * primero, volver a la lista despues de mirar un cupon releeria el disco entero; sin
     * el segundo, la app no serviria de nada sin senal.
     *
     * Se mide y se devuelve cuanto tardo. No es decoracion: es lo que permite demostrar
     * que el cache sirve, con un numero en vez de con una suposicion.
     */
    suspend fun catalogSnapshot(): CatalogSnapshot = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()

        DealsMemoryCache.get()?.let { cached ->
            return@withContext CatalogSnapshot(
                deals = cached,
                source = CatalogSource.MEMORY,
                elapsedMillis = elapsedSince(startedAt)
            )
        }

        val fromDisk = dealDao.activeDeals(nowIso()).first()
        DealsMemoryCache.put(deals = fromDisk)

        CatalogSnapshot(
            deals = fromDisk,
            source = CatalogSource.DISK,
            elapsedMillis = elapsedSince(startedAt)
        )
    }

    /**
     * Baja el catalogo del servidor y lo guarda.
     *
     * No lanza: si no hay red o el endpoint todavia no existe, se queda con lo que ya
     * tenia guardado. Un fallo al refrescar no puede dejar la pantalla vacia cuando hay
     * un catalogo perfectamente utilizable en el telefono.
     *
     * @return true si se actualizo desde el servidor
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (!online()) return@withContext false

        try {
            val remote = apiProvider().deals()
            dealDao.upsertAll(remote.map { it.toEntity(now()) })

            // El catalogo real manda: las filas sembradas se retiran en cuanto el
            // servidor responde, o el usuario veria cada cupon dos veces. Se borran
            // despues del upsert y no antes, para no dejar la pantalla vacia si la
            // escritura fallara a mitad.
            if (remote.isNotEmpty()) dealDao.deleteSeeded()

            // Se limpian los vencidos despues de guardar, no antes: si la peticion
            // fallara a mitad, no tiene sentido haber borrado ya lo que habia.
            dealDao.deleteExpired(nowIso())

            // El catalogo en memoria quedo obsoleto en cuanto se escribio el nuevo. Se
            // tira ahora y no se espera a que caduque: si no, la pantalla seguiria
            // mostrando el catalogo viejo hasta diez minutos despues de refrescarlo.
            DealsMemoryCache.invalidateAll()
            true
        } catch (error: Exception) {
            logWarning("No se pudo refrescar el catalogo de cupones", error)
            false
        }
    }

    /**
     * Siembra un catalogo local si no hay ninguno.
     *
     * Existe para que la pantalla tenga algo que mostrar en un telefono donde la app se
     * acaba de instalar y el backend no esta desplegado. No pisa nada: solo escribe si la
     * tabla esta vacia, asi que en cuanto el servidor responda una vez, el catalogo real
     * manda.
     */
    suspend fun seedIfEmpty(): Unit = withContext(Dispatchers.IO) {
        if (dealDao.count() > 0) return@withContext
        dealDao.upsertAll(sampleCatalog(now()))
        DealsMemoryCache.invalidateAll()
    }

    /**
     * Redime un cupon.
     *
     * Devuelve [RedeemResult.Synced] si llego al servidor, o [RedeemResult.Queued] si se
     * guardo para enviarlo despues. La pantalla distingue los dos casos: confirmado en
     * verde, pendiente en ambar. Es el mismo contrato que `JoinResult` en las
     * inscripciones, a proposito: dos flujos que se comportan igual deberian contarse
     * igual.
     */
    suspend fun redeem(
        dealId: String,
        duringBreak: Boolean?,
        breakMinutesLeft: Int?
    ): RedeemResult = withContext(Dispatchers.IO) {

        val context = DealInteractionDto(
            dealId = dealId,
            type = "REDEEMED",
            duringBreak = duringBreak,
            breakMinutesLeft = breakMinutesLeft
        )

        if (!online()) {
            return@withContext queueRedemption(dealId, duringBreak, breakMinutesLeft)
        }

        try {
            val response: RedemptionDto = apiProvider().redeemDeal(dealId, context)
            dealDao.markRedeemed(dealId, pendingSync = false, code = response.redemptionCode)
            DealsMemoryCache.invalidateAll()
            RedeemResult.Synced(response.redemptionCode)
        } catch (error: Exception) {
            // La red se cayo a mitad. Se encola igual, porque la intencion del usuario no
            // se puede perder por un fallo de transporte.
            logWarning("No se pudo redimir en linea, queda pendiente", error)
            queueRedemption(dealId, duringBreak, breakMinutesLeft)
        }
    }

    /**
     * Guarda la redencion para enviarla cuando vuelva la conexion.
     *
     * Tres pasos, y el orden importa:
     *
     * 1. Encolar, para que la intencion del usuario sobreviva al cierre de la app.
     * 2. Marcar el cupon como redimido y pendiente, para que la pantalla responda al
     *    instante. Es la actualizacion optimista, y el aviso en ambar es lo que la
     *    mantiene honesta.
     * 3. Pedirle a Android que lo mande cuando haya red.
     *
     * Primero se persiste y despues se muestra: al reves, si el proceso muriera entre los
     * dos pasos, la pantalla diria "redimido" sin que exista nada que enviar.
     */
    private suspend fun queueRedemption(
        dealId: String,
        duringBreak: Boolean?,
        breakMinutesLeft: Int?
    ): RedeemResult {
        // Se mira la cola primero: sin ella no hay a donde encolar, y asi tampoco se
        // toca ApiClient, que fuera de la app no esta inicializado.
        val queue = pendingActions
        val userId = if (queue == null) null else currentUserId()

        if (userId != null && queue != null) {
            queue.enqueueRedeemDeal(
                userId = userId,
                payload = RedeemDealPayload(dealId, duringBreak, breakMinutesLeft)
            )
            SyncScheduler.schedule(ApiClient.appContext)
        } else {
            // Sin sesion o sin cola no hay donde encolar. Se deja constancia en vez de
            // callarlo: si no, el cupon quedaria marcado como pendiente para siempre.
            logWarning(
                "No se pudo encolar la redencion de $dealId",
                IllegalStateException("sin sesion o sin cola de acciones pendientes")
            )
        }

        dealDao.markRedeemed(dealId, pendingSync = true, code = null)
        DealsMemoryCache.invalidateAll()
        return RedeemResult.Queued
    }

    /**
     * Reporta al servidor que unos cupones se mostraron o se abrieron.
     *
     * Es informacion de medicion para la BQ-11, no una accion del usuario, asi que no se
     * encola si falla: si no hay red, se pierde y ya. Encolarla significaria gastar disco
     * y bateria en reenviar eventos de interfaz, y falsearia las marcas de tiempo cuando
     * llegaran horas tarde.
     */
    suspend fun reportInteractions(batch: List<DealInteractionDto>) = withContext(Dispatchers.IO) {
        if (batch.isEmpty() || !online()) return@withContext

        try {
            apiProvider().reportDealInteractions(DealInteractionBatch(batch))
        } catch (error: Exception) {
            logWarning("No se pudieron reportar las interacciones con cupones", error)
        }
    }

    /**
     * Cuanto ha pasado, en milisegundos.
     *
     * nanoTime y no currentTimeMillis: el primero mide tiempo transcurrido de verdad y no
     * se ve afectado si el reloj del telefono se ajusta a mitad de la medicion.
     */
    private fun elapsedSince(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    /** La hora actual en ISO 8601, que es el formato con el que se comparan las fechas. */
    private fun nowIso(): String =
        OffsetDateTime.now().format(ISO_FORMATTER)

    private companion object {
        val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    }
}

/**
 * De donde salio el catalogo que se esta mostrando.
 *
 * Se distingue para poder demostrar que el cache funciona: la primera carga dice DISK, la
 * segunda dice MEMORY. Sin esta marca habria que creerselo.
 */
enum class CatalogSource {
    /** Estaba en memoria. Microsegundos. */
    MEMORY,

    /** Hubo que leer la base de datos. Milisegundos. */
    DISK
}

/** El catalogo, de donde salio y cuanto costo traerlo. */
data class CatalogSnapshot(
    val deals: List<DealEntity>,
    val source: CatalogSource,
    val elapsedMillis: Long
)

/**
 * Como termino una redencion.
 *
 * Son dos casos y no un booleano porque la pantalla tiene que decir cosas distintas, y
 * porque el caso confirmado trae el codigo que el estudiante ensena en el local.
 */
sealed interface RedeemResult {
    /** Llego al servidor. El codigo ya se puede ensenar. */
    data class Synced(val redemptionCode: String) : RedeemResult

    /** Quedo guardado en el telefono. Se enviara cuando vuelva la conexion. */
    data object Queued : RedeemResult
}

/** Convierte lo que manda el servidor a la fila que se guarda. */
private fun DealDto.toEntity(cachedAt: Long) = DealEntity(
    id = id,
    merchant = merchant,
    title = title,
    description = description,
    discountPercent = discountPercent,
    category = category,
    minutesNeeded = minutesNeeded,
    validUntil = validUntil,
    // El codigo y el estado de redencion NO vienen del servidor en el catalogo: son del
    // usuario en este telefono. Se dejan nulos y en false aqui, y `@Upsert` conserva los
    // valores que ya hubiera en la fila. Si se pusieran a false sin mas, cada refresco
    // borraria las redenciones del usuario.
    redemptionCode = null,
    imageUrl = imageUrl,
    cachedAt = cachedAt
)

/**
 * Catalogo de ejemplo, para cuando el backend no esta disponible.
 *
 * Los minutos de cada cupon estan puestos con criterio y no al azar: son el campo que se
 * cruza con los huecos entre clases, asi que un cafe cabe en un hueco corto y un
 * almuerzo no. Si todos duraran lo mismo, la BQ-11 no podria distinguir "no le
 * interesaba" de "no le alcanzaba el tiempo".
 */
private fun sampleCatalog(cachedAt: Long): List<DealEntity> {
    val enUnMes = OffsetDateTime.now().plusMonths(1)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US))

    fun deal(
        id: String, merchant: String, title: String, description: String,
        discount: Int, category: String, minutes: Int, image: String
    ) = DealEntity(
        id = id, merchant = merchant, title = title, description = description,
        discountPercent = discount, category = category, minutesNeeded = minutes,
        validUntil = enUnMes, redemptionCode = null, imageUrl = image, cachedAt = cachedAt
    )

    return listOf(
        deal("seed-cafe-andino", "Cafe Andino", "20% en cafe caliente",
            "Valido de lunes a viernes antes de las 11 am.", 20, "CAFE", 10,
            "https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?w=800"),
        deal("seed-juan-valdez", "Juan Valdez Centro", "Segundo cafe a mitad de precio",
            "Presentando carne estudiantil.", 50, "CAFE", 15,
            "https://images.unsplash.com/photo-1509042239860-f550ce710b93?w=800"),
        deal("seed-papeleria-19", "Papeleria La 19", "15% en fotocopias e impresiones",
            "No acumulable con otras promociones.", 15, "PAPELERIA", 10,
            "https://images.unsplash.com/photo-1583485088034-697b5bc54ccd?w=800"),
        deal("seed-crepes", "Crepes & Waffles La Macarena", "25% en plato fuerte",
            "De lunes a jueves al almuerzo.", 25, "COMIDA", 45,
            "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=800"),
        deal("seed-wok", "Wok Zona G", "2x1 en bowls",
            "Solo para consumo en el local.", 50, "COMIDA", 50,
            "https://images.unsplash.com/photo-1512058564366-18510be2db19?w=800"),
        deal("seed-bodytech", "Bodytech Chapinero", "Primer mes al 40%",
            "Incluye valoracion fisica inicial.", 40, "DEPORTE", 90,
            "https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=800"),
        deal("seed-cine", "Cine Colombia Avenida Chile", "30% en entrada general",
            "De domingo a jueves.", 30, "ENTRETENIMIENTO", 120,
            "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=800")
    )
}
