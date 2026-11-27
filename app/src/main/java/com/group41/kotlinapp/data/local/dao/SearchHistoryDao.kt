package com.group41.kotlinapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.group41.kotlinapp.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Las consultas sobre el historial de busquedas.
 *
 * Un DAO no tiene cuerpo: se declaran las consultas y Room genera el codigo que las
 * ejecuta al compilar. La ventaja de que sea en tiempo de compilacion y no al ejecutar
 * es que un error de SQL, o un nombre de columna mal escrito, rompe la compilacion en vez
 * de reventar en el telefono del usuario.
 */
@Dao
interface SearchHistoryDao {

    /**
     * Guarda una busqueda.
     *
     * Aqui no hace falta `onConflict`: el id es automatico, asi que nunca choca.
     */
    @Insert
    suspend fun insert(search: SearchHistoryEntity): Long

    /**
     * Las ultimas busquedas del usuario, de la mas reciente a la mas vieja.
     *
     * Devuelve `Flow` y no una lista para que la pantalla se actualice sola cuando se
     * guarda una busqueda nueva, sin tener que volver a preguntar.
     *
     * El LIMIT no es decoracion: sin el, un usuario con mil busquedas cargaria mil filas
     * en memoria para mostrar diez.
     */
    @Query(
        """
        SELECT * FROM search_history
        WHERE userId = :userId
        ORDER BY searchedAt DESC
        LIMIT :limit
        """
    )
    fun recentSearches(userId: String, limit: Int = 10): Flow<List<SearchHistoryEntity>>

    /**
     * Las zonas que el usuario busca mas, redondeando las coordenadas.
     *
     * El redondeo a dos decimales es la clave de la consulta. Dos busquedas nunca tienen
     * exactamente las mismas coordenadas, porque el sensor da un valor algo distinto cada
     * vez. Sin redondear, cada busqueda seria su propio grupo y el GROUP BY no agruparia
     * nada. Dos decimales son algo mas de un kilometro, que es la escala a la que tiene
     * sentido decir "esta es la misma zona".
     */
    @Query(
        """
        SELECT
            ROUND(latitude, 2)  AS zoneLatitude,
            ROUND(longitude, 2) AS zoneLongitude,
            COUNT(*)            AS searchCount,
            MAX(searchedAt)     AS lastSearchedAt
        FROM search_history
        WHERE userId = :userId
        GROUP BY zoneLatitude, zoneLongitude
        ORDER BY searchCount DESC, lastSearchedAt DESC
        LIMIT :limit
        """
    )
    suspend fun favoriteZones(userId: String, limit: Int = 3): List<FavoriteZone>

    /**
     * Cuantas busquedas se respondieron sin internet y cuantas con el servidor.
     *
     * Es una sola consulta con dos subconsultas en vez de dos consultas separadas para
     * que los dos numeros vengan del mismo instante. Con dos consultas, una busqueda
     * hecha entre la primera y la segunda daria totales que no cuadran.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM search_history WHERE userId = :userId AND wasOffline = 1) AS offlineCount,
            (SELECT COUNT(*) FROM search_history WHERE userId = :userId AND wasOffline = 0) AS onlineCount
        """
    )
    suspend fun offlineUsage(userId: String): OfflineUsage

    /**
     * Borra lo que sobra del historial, dejando solo las N busquedas mas recientes.
     *
     * Hace falta porque esta tabla crece sola: cada busqueda agrega una fila y nada la
     * quita. Sin esta limpieza, un usuario que use la app un año acabaria con miles de
     * filas que nunca se van a mirar.
     *
     * La subconsulta selecciona los ids que SI se conservan y borra todo lo demas. Se
     * escribe asi, y no con un OFFSET en el DELETE, porque SQLite no admite LIMIT en un
     * DELETE sin una opcion de compilacion que Android no activa.
     */
    @Query(
        """
        DELETE FROM search_history
        WHERE userId = :userId
          AND id NOT IN (
            SELECT id FROM search_history
            WHERE userId = :userId
            ORDER BY searchedAt DESC
            LIMIT :keep
          )
        """
    )
    suspend fun trimTo(userId: String, keep: Int)

    /**
     * Guarda la busqueda y limpia el exceso, como una sola operacion.
     *
     * `@Transaction` hace que las dos sentencias se traten como una. Sin eso, si la app
     * muriera entre el insert y el borrado, la tabla quedaria con una fila de mas; y, peor,
     * otra corrutina podria leer el historial justo en ese punto intermedio.
     *
     * Es una funcion con cuerpo dentro de una interfaz, lo que Kotlin permite y Room
     * aprovecha: el metodo se ejecuta tal cual, envuelto en la transaccion.
     */
    @Transaction
    suspend fun recordSearch(search: SearchHistoryEntity, keep: Int = 50) {
        insert(search)
        trimTo(search.userId, keep)
    }

    /** Borra el historial del usuario. Lo llama el boton de los ajustes. */
    @Query("DELETE FROM search_history WHERE userId = :userId")
    suspend fun clearFor(userId: String)
}

/**
 * Una zona en la que el usuario busca a menudo.
 *
 * Room construye estos objetos a partir de los nombres de las columnas de la consulta,
 * asi que los nombres de aqui y los alias del SELECT tienen que coincidir exactamente.
 * No es una tabla: es la forma de un resultado.
 */
data class FavoriteZone(
    val zoneLatitude: Double,
    val zoneLongitude: Double,
    val searchCount: Int,
    val lastSearchedAt: Long
)

/** El reparto entre busquedas resueltas sin internet y con el servidor. */
data class OfflineUsage(
    val offlineCount: Int,
    val onlineCount: Int
) {
    /** Que porcentaje de las busquedas se respondio sin internet. */
    val offlinePercentage: Int
        get() {
            val total = offlineCount + onlineCount
            // Sin busquedas no hay porcentaje que calcular, y dividir por cero rompe.
            return if (total == 0) 0 else offlineCount * 100 / total
        }
}
