package com.group41.kotlinapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.group41.kotlinapp.data.local.entity.DealEntity
import kotlinx.coroutines.flow.Flow

/**
 * Las consultas sobre el catalogo de cupones guardado en el telefono.
 */
@Dao
interface DealDao {

    /**
     * Los cupones vigentes, del mayor descuento al menor.
     *
     * El filtro de vigencia se hace en SQL y no en Kotlin a proposito. Podria leerse todo
     * y filtrar despues, pero entonces el indice sobre `validUntil` no serviria de nada y
     * se cargarian en memoria cupones que nadie va a ver.
     *
     * La comparacion de fechas funciona porque ISO 8601 esta hecho para ordenarse como
     * texto: los campos van de mayor a menor unidad y con ceros a la izquierda, asi que
     * comparar las cadenas da el mismo resultado que comparar los instantes. Es la razon
     * por la que se guardan como TEXT y no hace falta convertirlos.
     *
     * `validUntil IS NULL` primero en la condicion: un cupon sin fecha no vence, y sin esa
     * rama desapareceria de la lista por un NULL en la comparacion.
     */
    @Query(
        """
        SELECT * FROM deals
        WHERE validUntil IS NULL OR validUntil >= :now
        ORDER BY discountPercent DESC
        """
    )
    fun activeDeals(now: String): Flow<List<DealEntity>>

    /**
     * Los cupones vigentes que caben en el tiempo disponible.
     *
     * Es la consulta que conecta el catalogo con el horario del estudiante, y la que hace
     * util la BQ-11: no tiene sentido ofrecerle un almuerzo con descuento a alguien que
     * tiene quince minutos entre clases.
     */
    @Query(
        """
        SELECT * FROM deals
        WHERE (validUntil IS NULL OR validUntil >= :now)
          AND minutesNeeded <= :availableMinutes
        ORDER BY discountPercent DESC
        """
    )
    fun dealsFittingIn(now: String, availableMinutes: Int): Flow<List<DealEntity>>

    @Query("SELECT * FROM deals WHERE id = :id")
    suspend fun byId(id: String): DealEntity?

    /** Los que este usuario ya redimio, del mas reciente al mas antiguo. */
    @Query("SELECT * FROM deals WHERE redeemed = 1 ORDER BY cachedAt DESC")
    fun redeemedDeals(): Flow<List<DealEntity>>

    /**
     * Cuantos cupones hay por categoria, solo entre los vigentes.
     *
     * Alimenta los filtros de la pantalla: un chip de categoria vacio no deberia
     * aparecer, y averiguarlo contando en Kotlin obligaria a traer todas las filas.
     */
    @Query(
        """
        SELECT category, COUNT(*) AS total
        FROM deals
        WHERE validUntil IS NULL OR validUntil >= :now
        GROUP BY category
        ORDER BY total DESC
        """
    )
    suspend fun countByCategory(now: String): List<CategoryCount>

    /**
     * Guarda o actualiza el catalogo descargado.
     *
     * `@Upsert` y no `@Insert(REPLACE)`: REPLACE borra la fila y la vuelve a insertar, lo
     * que perderia `redeemed` y `pendingSync` en cada refresco del catalogo. El usuario
     * veria sus cupones redimidos volver a estar disponibles, y peor, una redencion que
     * todavia no habia llegado al servidor desapareceria sin dejar rastro.
     */
    @Upsert
    suspend fun upsertAll(deals: List<DealEntity>)

    /**
     * Marca un cupon como redimido en el telefono.
     *
     * `pendingSync` dice si el backend ya lo sabe. Se actualiza solo lo que cambia en vez
     * de reemplazar la fila entera, para no pisar el resto del cupon con una copia que
     * quien llama podria tener desactualizada.
     */
    @Query(
        """
        UPDATE deals
        SET redeemed = 1, pendingSync = :pendingSync, redemptionCode = :code
        WHERE id = :id
        """
    )
    suspend fun markRedeemed(id: String, pendingSync: Boolean, code: String?)

    /** Confirma que una redencion encolada ya llego al servidor. */
    @Query("UPDATE deals SET pendingSync = 0 WHERE id = :id")
    suspend fun clearPendingSync(id: String)

    /**
     * Deshace una redencion que el servidor rechazo definitivamente.
     *
     * Pasa cuando el cupon vencio o el comercio lo retiro mientras estaba encolado. Hay
     * que revertir y no solo quitar la bandera de pendiente: si se dejara `redeemed = 1`,
     * el usuario creeria que tiene un descuento que el comercio no va a aceptar, y se
     * enteraria en la caja. Es mejor que lo vea disponible otra vez, o que desaparezca
     * por vencido, que darle una promesa falsa.
     */
    @Query("UPDATE deals SET redeemed = 0, pendingSync = 0, redemptionCode = NULL WHERE id = :id")
    suspend fun revertRedemption(id: String)

    /**
     * Borra los cupones vencidos, salvo los que el usuario redimio.
     *
     * Los redimidos se conservan aunque venzan: son su historial, y ademas uno redimido
     * sin sincronizar todavia tiene que llegar al servidor. Borrarlo perderia la accion
     * del usuario, que es lo unico que no se puede volver a descargar.
     */
    @Query("DELETE FROM deals WHERE validUntil IS NOT NULL AND validUntil < :now AND redeemed = 0")
    suspend fun deleteExpired(now: String)

    /**
     * Borra los cupones de ejemplo cuando llega el catalogo real del servidor.
     *
     * La siembra existe para que la pantalla funcione en un telefono donde el backend no
     * esta disponible, pero sus filas llevan ids propios (`seed-*`) que nunca coinciden
     * con los del servidor. Sin este borrado, el primer refresco exitoso deja las dos
     * versiones del catalogo a la vez y el usuario ve cada cupon duplicado.
     *
     * Los redimidos se conservan: aunque sean de ejemplo, son una accion del usuario y
     * una redencion encolada todavia tiene que llegar al servidor.
     */
    @Query("DELETE FROM deals WHERE id LIKE 'seed-%' AND redeemed = 0")
    suspend fun deleteSeeded()

    @Query("SELECT COUNT(*) FROM deals")
    suspend fun count(): Int
}

/** Cuantos cupones vigentes hay en una categoria. */
data class CategoryCount(
    val category: String,
    val total: Int
)
