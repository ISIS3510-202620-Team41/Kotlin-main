package com.group41.kotlinapp.cache

import com.group41.kotlinapp.data.local.entity.DealEntity

/**
 * El catalogo de cupones en memoria, con expulsion LRU y caducidad.
 *
 * Es lo que pide el issue #43. Existe aparte de `ActivityMemoryCache`, que guarda
 * actividades, porque son dos tipos de dato con reglas distintas: una actividad cambia
 * cuando alguien se inscribe, mientras que un cupon es practicamente estatico hasta que
 * vence. Meterlos en el mismo cache obligaria a darles el mismo tiempo de vida y el mismo
 * tope de entradas, y ninguno de los dos valores sirve para los dos.
 *
 *
 * LOS TRES NIVELES, Y POR QUE ESTE ES EL PRIMERO
 *
 * El catalogo se busca en tres sitios, en este orden:
 *
 * 1. **Memoria** (esta clase): microsegundos. Es donde cae la gran mayoria de las
 *    consultas, porque el usuario entra y sale de la pantalla varias veces por sesion.
 * 2. **Room**: milisegundos. Sobrevive al cierre de la app, asi que es lo que hace que el
 *    catalogo este ahi al abrirla sin internet.
 * 3. **Red**: cientos de milisegundos, y solo si hay conexion.
 *
 * Cada nivel existe porque el siguiente es entre mil y un millon de veces mas lento. Sin
 * el primero, volver a la lista despues de ver un cupon releeria el disco entero; sin el
 * segundo, la app no serviria de nada sin senal.
 *
 *
 * POR QUE SE APOYA EN TimedCache
 *
 * No reimplementa el LRU: usa el que ya existe, que trae la expulsion por uso, la
 * caducidad por tiempo, la invalidacion al escribir y los contadores de acierto. Esta
 * clase solo decide los parametros y que se guarda bajo que clave. Reescribir el mapa
 * aqui habria sido duplicar codigo ya probado por once tests.
 */
object DealsMemoryCache {

    /**
     * Cuanto vale el catalogo antes de volver a pedirlo.
     *
     * Diez minutos, bastante mas que los dos de las busquedas por cercania, y la razon es
     * la naturaleza del dato: una actividad cambia cuando otro usuario se inscribe, pero
     * un cupon solo cambia cuando el comercio decide cambiarlo. Servir un catalogo de
     * hace diez minutos no le miente a nadie.
     *
     * El limite real no es este tiempo sino la vigencia de cada cupon, y esa se comprueba
     * aparte en la consulta de la base. Un cupon que vence mientras esta cacheado queda
     * filtrado igual.
     */
    private const val TTL_MILLIS = 10L * 60 * 1000

    /**
     * Cuantas listas distintas se guardan.
     *
     * Son listas, no cupones sueltos: la clave es el filtro aplicado (todas, o una
     * categoria). Con media docena de categorias, ocho entradas cubren lo que un usuario
     * consulta en una sesion sin guardar combinaciones que no va a repetir.
     */
    private const val MAX_ENTRIES = 8

    private val cache = TimedCache<String, List<DealEntity>>(
        maxEntries = MAX_ENTRIES,
        ttlMillis = TTL_MILLIS,
        name = "deals"
    )

    /** Los contadores, para poder mostrar la tasa de acierto en los ajustes. */
    val stats get() = cache.stats

    /**
     * El catalogo guardado para un filtro, o null si hay que ir a buscarlo.
     *
     * @param category null significa "todas las categorias".
     */
    fun get(category: String? = null): List<DealEntity>? = cache.get(keyFor(category))

    fun put(category: String? = null, deals: List<DealEntity>) {
        cache.put(keyFor(category), deals)
    }

    /**
     * Tira todo lo guardado.
     *
     * Se vacia entero y no por categoria porque un cupon redimido cambia de estado en
     * todas las listas donde aparecia, y desde aqui no se sabe cuales son. Con ocho
     * entradas como tope, vaciarlo cuesta nada y reconstruirlo es una lectura de disco.
     *
     * Se llama al redimir y al refrescar el catalogo. Esa invalidacion al escribir es lo
     * que evita el error clasico: redimir un cupon, volver atras, y verlo otra vez como
     * disponible porque la lista cacheada es de antes.
     */
    fun invalidateAll() {
        cache.clear()
    }

    fun size(): Int = cache.size()

    /**
     * La clave de una lista.
     *
     * El prefijo evita que una categoria llamada "all" choque con la lista completa. Es
     * un detalle pequeno que, de fallar, serviria el catalogo entero cuando el usuario
     * pidio una categoria, y costaria mucho encontrarlo.
     */
    private fun keyFor(category: String?): String =
        if (category == null) "all" else "category:$category"
}
