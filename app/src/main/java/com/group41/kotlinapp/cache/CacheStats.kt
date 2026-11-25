package com.group41.kotlinapp.cache

import com.group41.kotlinapp.storage.FastKeyValueStore
import java.util.concurrent.atomic.AtomicLong

/**
 * Cuenta que tan bien esta funcionando un cache.
 *
 * Para que sirve: sin esto, decir "el cache ayuda" es una suposicion. Un cache mal
 * dimensionado puede estar fallando el noventa por ciento de las veces y nadie se daria
 * cuenta, porque la app funciona igual: cada fallo simplemente va a buscar el dato a su
 * fuente. El sintoma no es un error, es lentitud, y la lentitud no se nota en el
 * telefono del desarrollador.
 *
 * Se cuentan cuatro cosas distintas a proposito, porque cada una se arregla de otra forma:
 *
 * - `hits`: el dato estaba y servia. Es lo que se quiere maximizar.
 * - `misses`: el dato no estaba. Si son muchos, o el cache es muy pequeño o las claves
 *   estan mal elegidas y nunca se repiten.
 * - `stale`: el dato estaba pero habia caducado. Si son muchos, el tiempo de vida es
 *   demasiado corto para como se usa la app.
 * - `evictions`: el dato se tiro para hacer sitio. Si son muchas, el tope de entradas es
 *   demasiado bajo.
 *
 * Juntar `stale` con `misses` seria perder justamente esa informacion: las dos cosas
 * acaban yendo a buscar el dato, pero se corrigen de manera opuesta.
 *
 * Los contadores son [AtomicLong] porque los incrementa cualquier hilo que use el cache,
 * y un `Long` normal se puede corromper si dos hilos lo suben a la vez: leer, sumar uno y
 * escribir no es una sola operacion.
 */
class CacheStats(private val name: String) {

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val stale = AtomicLong()
    private val evictions = AtomicLong()

    fun recordHit() {
        hits.incrementAndGet()
    }

    fun recordMiss() {
        misses.incrementAndGet()
    }

    fun recordStale() {
        stale.incrementAndGet()
    }

    fun recordEviction() {
        evictions.incrementAndGet()
    }

    /** Una foto de los contadores en este instante. */
    fun snapshot(): Snapshot = Snapshot(
        name = name,
        hits = hits.get(),
        misses = misses.get(),
        stale = stale.get(),
        evictions = evictions.get()
    )

    /** Vuelve todo a cero. Lo usa el boton de limpiar de los ajustes, y los tests. */
    fun reset() {
        hits.set(0)
        misses.set(0)
        stale.set(0)
        evictions.set(0)
    }

    /**
     * Guarda los contadores para que sobrevivan al cierre de la app.
     *
     * Van a MMKV y no a SharedPreferences por la misma razon de siempre: son valores que
     * se tocan constantemente, y SharedPreferences reescribiria su XML completo en cada
     * incremento. Aqui ni siquiera se incrementa clave por clave; se vuelca el total de
     * vez en cuando, que es mas barato todavia.
     *
     * Sirve para poder responder "cual es la tasa de acierto" con un numero de varias
     * sesiones de uso real, y no solo de los ultimos tres minutos.
     */
    fun persist() {
        val current = snapshot()
        FastKeyValueStore.putCounters(
            prefix = "cache.$name",
            hits = current.hits,
            misses = current.misses,
            stale = current.stale,
            evictions = current.evictions
        )
    }

    /**
     * Los contadores de un cache en un momento dado.
     *
     * Es una clase aparte y no los campos vivos para que quien los lea vea un conjunto
     * coherente: si se leyeran los cuatro contadores uno por uno, otro hilo podria
     * cambiarlos entre lectura y lectura y los numeros no cuadrarian entre si.
     */
    data class Snapshot(
        val name: String,
        val hits: Long,
        val misses: Long,
        val stale: Long,
        val evictions: Long
    ) {
        /** Cuantas consultas hubo en total. */
        val lookups: Long get() = hits + misses + stale

        /**
         * Que porcentaje de las consultas se respondio desde el cache.
         *
         * Sin consultas se devuelve cero en vez de dividir, que reventaria. Y se devuelve
         * cero y no cien: un cache que nadie ha consultado no ha acertado nada.
         */
        val hitRatePercent: Int
            get() = if (lookups == 0L) 0 else (hits * 100 / lookups).toInt()

        override fun toString(): String =
            "$name: $hitRatePercent% acierto ($hits aciertos, $misses fallos, " +
                "$stale caducados, $evictions expulsados)"
    }
}
