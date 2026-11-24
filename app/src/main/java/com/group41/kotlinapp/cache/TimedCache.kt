package com.group41.kotlinapp.cache

/**
 * Un cache en memoria donde las entradas caducan por tiempo, y no solo por falta de sitio.
 *
 * Que problema resuelve, y por que hacia falta uno nuevo:
 *
 * El cache de actividades que ya tenia el proyecto expulsa entradas por uso: cuando se
 * llena, tira la que se consulto hace mas tiempo. Eso acota la memoria, pero no dice nada
 * sobre si el dato sigue siendo valido. Una actividad guardada hace tres dias se sirve
 * exactamente igual que una guardada hace tres segundos, mientras no se haya llenado el
 * cache. Para datos que el servidor puede cambiar (cuantos participantes tiene una
 * actividad, si ya esta llena) eso significa mostrar informacion falsa sin saberlo.
 *
 * Este cache añade las dos cosas que faltaban:
 *
 * - **Caducidad.** Cada entrada recuerda cuando se guardo y deja de servir pasado su
 *   tiempo de vida. Caducar no es lo mismo que expulsar: una entrada caducada puede ser
 *   la unica del cache y seguir sin servir.
 * - **Invalidacion al escribir.** Cuando la app cambia un dato, se tira esa entrada de
 *   inmediato en vez de esperar a que caduque. Es lo que evita que inscribirse en una
 *   actividad y volver atras muestre el estado anterior.
 *
 * Es generico (`K`, `V`) a proposito. El cache anterior solo guarda actividades; este
 * sirve para cualquier cosa, y de hecho se usa con una clave compuesta de coordenadas y
 * radio, que no es un identificador de nada.
 *
 * **Como se prueba el tiempo.** El reloj entra por el constructor en lugar de llamar
 * directamente a `System.currentTimeMillis()`. Sin eso, probar la caducidad obligaria a
 * que el test durmiera de verdad: un tiempo de vida de un minuto significaria un test de
 * un minuto, y uno de una hora no se podria probar en absoluto. Con el reloj inyectado,
 * el test adelanta la hora cuanto quiera y corre en microsegundos.
 *
 *
 * LA ESTRUCTURA Y SUS PARAMETROS
 *
 * Por dentro es un `LinkedHashMap`, que es un HashMap que ademas mantiene sus entradas
 * enlazadas en un orden. Ese enlace es lo que permite saber cual es la "mas vieja" sin
 * recorrer el mapa entero, y es lo que lo convierte en la base de un LRU. Un HashMap a
 * secas no sirve: no tiene noción de orden, asi que al llenarse no sabria que tirar.
 *
 * Los tres parametros del constructor del mapa son tres decisiones:
 *
 * - **initialCapacity** = `(maxEntries / 0.75) + 1`. Es cuantas cubetas reserva la tabla al
 *   nacer. No se le pasa `maxEntries` tal cual: una tabla hash se rehace (crea una tabla
 *   mas grande y recoloca todo) cuando se llena por encima de su factor de carga. Con una
 *   capacidad igual al tope, la tabla se rehace al llegar al 75 por ciento del tope, que es
 *   justo cuando el cache esta a punto de serle util. Dividiendo por el factor de carga, la
 *   tabla ya nace con cubetas para todas las entradas y no se rehace nunca.
 *
 * - **loadFactor** = `0.75`. El punto de llenado a partir del cual la tabla se rehace. Se
 *   deja el valor por defecto de Java porque es el compromiso conocido: mas bajo gasta
 *   memoria en cubetas vacias, mas alto aumenta las colisiones. Lo que importa aqui no es
 *   su valor, sino que entra en el calculo de la capacidad.
 *
 * - **accessOrder** = `true`. Es lo que hace que sea LRU y no FIFO. Con `true`, consultar
 *   una entrada la mueve al final de la lista, asi que la primera es la que lleva mas
 *   tiempo sin usarse. Con `false` el orden seria el de insercion, y al llenarse se tiraria
 *   la entrada mas antigua aunque fuera la que mas se consulta.
 *
 * Y la expulsion se engancha en **removeEldestEntry**, un metodo que la propia clase de
 * Java llama despues de cada insercion para preguntar si hay que quitar la mas vieja.
 * Devolver `size > maxEntries` es lo que acota el cache. Ese enganche existe precisamente
 * para construir caches, y es mejor que comprobar el tamaño a mano desde `put`, porque se
 * ejecuta tambien cuando la insercion viene de cualquier otro camino.
 */
class TimedCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    name: String = "timed",
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Los contadores de este cache. Se exponen para poder mirarlos desde los ajustes. */
    val stats = CacheStats(name)

    init {
        // El cache se apunta solo en el listado al crearse. Asi la pantalla de ajustes no
        // tiene que conocer uno por uno los caches que existen, y uno nuevo aparece sin que
        // haya que acordarse de ir a registrarlo a mano.
        CacheRegistry.register(name, stats)
    }

    /**
     * Una entrada guardada, junto con el momento en que se guardo.
     *
     * La fecha va dentro de la entrada y no en un mapa aparte porque asi es imposible que
     * una entrada exista sin su fecha: se guardan y se tiran juntas.
     */
    private class Entry<V>(val value: V, val storedAt: Long)

    /**
     * El mapa que guarda todo.
     *
     * Tres detalles, y los tres son decisiones:
     *
     * - `accessOrder = true` (el tercer parametro) es lo que lo convierte en LRU. Con
     *   `false`, el orden seria el de insercion y se tiraria la entrada mas antigua en vez
     *   de la menos usada, que no es lo mismo.
     *
     * - La capacidad inicial se calcula a partir del tope y no se pasa el tope tal cual.
     *   Un HashMap se rehace cuando se llena al 75 por ciento, asi que darle una capacidad
     *   igual al tope significa que se rehace justo antes de llegar al limite, que es el
     *   peor momento posible. Dividiendo por el factor de carga, la tabla ya nace con
     *   sitio para todas las entradas y no se rehace nunca.
     *
     * - `removeEldestEntry` es el enganche que la propia clase de Java ofrece para que un
     *   LinkedHashMap se comporte como cache. Devolver `true` hace que se quite la entrada
     *   mas vieja despues de insertar. Aqui se aprovecha para contar la expulsion.
     */
    private val entries = object : LinkedHashMap<K, Entry<V>>(
        (maxEntries / LOAD_FACTOR).toInt() + 1,
        LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean {
            val full = size > maxEntries
            if (full) stats.recordEviction()
            return full
        }
    }

    /**
     * El valor guardado, o null si no esta o ya caduco.
     *
     * Una entrada caducada se borra aqui mismo en vez de dejarla ocupando sitio. Es la
     * forma mas simple de limpiar: no hace falta un hilo que recorra el cache buscando
     * caducados, porque cada consulta limpia lo que se encuentra.
     *
     * `@Synchronized` porque esta funcion escribe (borra la caducada, y la consulta
     * reordena el LRU). Un LinkedHashMap no es seguro entre hilos, y aqui lo usan la
     * pantalla, el repositorio y el worker de sincronizacion.
     */
    @Synchronized
    fun get(key: K): V? {
        val entry = entries[key]

        if (entry == null) {
            stats.recordMiss()
            return null
        }

        if (clock() - entry.storedAt >= ttlMillis) {
            entries.remove(key)
            stats.recordStale()
            return null
        }

        stats.recordHit()
        return entry.value
    }

    /** Guarda un valor, con la hora actual como fecha de nacimiento. */
    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = Entry(value, clock())
    }

    /**
     * Tira una entrada concreta.
     *
     * Esto es la invalidacion al escribir, y es la diferencia entre un cache correcto y
     * uno que miente: cuando la app cambia un dato, el cache tiene que enterarse en ese
     * momento. Esperar a que caduque significa servir el valor viejo durante todo el
     * tiempo de vida restante.
     */
    @Synchronized
    fun invalidate(key: K) {
        entries.remove(key)
    }

    /**
     * Tira todo lo guardado.
     *
     * Hace falta cuando cambia algo que afecta a todas las entradas a la vez: cerrar
     * sesion, por ejemplo. Invalidar clave por clave en ese caso exigiria saber que claves
     * hay, y el cache no tiene por que contarlo.
     */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Cuantas entradas hay ahora, contando las que ya caducaron pero nadie ha consultado. */
    @Synchronized
    fun size(): Int = entries.size

    private companion object {
        /**
         * El factor de carga por defecto de un HashMap de Java.
         *
         * Se escribe aqui como constante porque se usa para dos cosas distintas: pasarselo
         * al mapa y calcular la capacidad inicial. Si estuvieran escritos por separado y
         * alguien cambiara uno, el calculo dejaria de cuadrar sin que nada avisara.
         */
        const val LOAD_FACTOR = 0.75f
    }
}
