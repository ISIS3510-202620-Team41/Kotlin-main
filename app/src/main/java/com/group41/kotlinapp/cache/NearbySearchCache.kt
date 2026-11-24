package com.group41.kotlinapp.cache

import kotlin.math.round

/**
 * Guarda el resultado de las busquedas por cercania ya calculadas.
 *
 * Que ahorra, y por que merece la pena:
 *
 * Una busqueda por cercania sin conexion lee todas las actividades del disco y calcula la
 * distancia Haversine a cada una. Con pocas actividades es cuestion de milisegundos, pero
 * el trabajo se repite entero cada vez: el usuario cambia el radio, vuelve atras, entra
 * otra vez, y las mismas cuentas se hacen de nuevo sobre los mismos datos.
 *
 * Este cache evita ese trabajo cuando la pregunta es la misma.
 *
 * **El problema de la clave, que es lo interesante.** La clave natural seria la posicion
 * del usuario y el radio. Pero el sensor nunca devuelve dos veces la misma coordenada: da
 * 4.6512 y luego 4.65118, aunque el telefono no se haya movido un centimetro. Usadas tal
 * cual, dos busquedas desde el mismo sitio producirian claves distintas y el cache no
 * acertaria jamas. Seria codigo muerto que ademas gasta memoria.
 *
 * La solucion es redondear la posicion a tres decimales, que son unos cien metros. A esa
 * escala, moverse dentro de la misma manzana cae en la misma clave, y el resultado sigue
 * siendo correcto porque cien metros no cambian que actividades caen dentro de un radio
 * de kilometros.
 *
 * Es el mismo truco que usa la consulta de zonas favoritas del historial, pero con una
 * precision distinta: ahi se redondea a dos decimales, poco mas de un kilometro, porque
 * agrupar "la misma zona" es una pregunta mas gruesa que "el mismo punto de partida".
 */
object NearbySearchCache {

    /**
     * Cuanto vale un resultado antes de volver a calcularlo.
     *
     * Dos minutos es corto a proposito. Lo que puede cambiar en ese rato no es la posicion
     * de las actividades, que no se mueven, sino el estado del usuario respecto a ellas:
     * se inscribe, se desinscribe, una se llena. Un tiempo largo serviria resultados donde
     * una actividad aparece como disponible cuando el usuario ya se inscribio.
     *
     * De todas formas, la inscripcion no espera estos dos minutos: invalida el cache en el
     * momento. El tiempo de vida es la red de seguridad para lo que cambia sin que la app
     * se entere, no el mecanismo principal.
     */
    private const val TTL_MILLIS = 2L * 60 * 1000

    /**
     * Tope de busquedas guardadas.
     *
     * Veinte son suficientes: una persona busca desde unos pocos sitios (su casa, la
     * universidad, el trabajo) con unos pocos radios. Mas entradas solo guardarian
     * busquedas que no se van a repetir.
     */
    private const val MAX_ENTRIES = 20

    private val cache = TimedCache<Key, List<String>>(
        maxEntries = MAX_ENTRIES,
        ttlMillis = TTL_MILLIS,
        name = "nearby"
    )

    val stats get() = cache.stats

    /**
     * Los identificadores de las actividades que respondieron a esta busqueda, o null si
     * hay que calcularla.
     *
     * Se guardan identificadores y no las actividades enteras por dos razones. Pesan mucho
     * menos, y sobre todo no se quedan congeladas: los datos de cada actividad se leen
     * despues de donde esten, asi que si una cambio de titulo o de participantes, el
     * usuario ve el dato nuevo. Lo que se cachea es el resultado del calculo de cercania,
     * que es la parte cara, no el contenido.
     */
    fun get(latitude: Double, longitude: Double, radiusKm: Double): List<String>? =
        cache.get(Key(round3(latitude), round3(longitude), radiusKm))

    fun put(latitude: Double, longitude: Double, radiusKm: Double, activityIds: List<String>) {
        cache.put(Key(round3(latitude), round3(longitude), radiusKm), activityIds)
    }

    /**
     * Tira todas las busquedas guardadas.
     *
     * Se vacia entero y no por clave porque un cambio en una actividad puede afectar a
     * cualquier busqueda: no se sabe desde que posiciones aparecia. Con veinte entradas
     * como maximo, vaciarlo cuesta nada, y recalcular una busqueda son milisegundos. El
     * cache barato de reconstruir se tira sin pensarlo; el caro es el que hay que
     * invalidar con precision.
     */
    fun invalidateAll() {
        cache.clear()
    }

    fun size(): Int = cache.size()

    /**
     * Tres decimales, unos cien metros.
     *
     * Se multiplica, se redondea y se divide porque Kotlin no trae un redondeo a N
     * decimales para Double. `round` solo redondea al entero mas cercano.
     */
    private fun round3(value: Double): Double = round(value * 1000) / 1000

    /**
     * La clave de una busqueda.
     *
     * Es una `data class` y no un String armado a mano porque asi el compilador genera
     * `equals` y `hashCode` por los tres campos, que es exactamente lo que el mapa
     * necesita. Con un String habria que acordarse del formato en los dos sitios, y un
     * separador distinto o un decimal de mas haria que la clave no coincidiera nunca.
     */
    private data class Key(
        val latitude: Double,
        val longitude: Double,
        val radiusKm: Double
    )
}
