package com.group41.kotlinapp.cache

import androidx.collection.ArrayMap

/**
 * El listado de caches de la app, para poder mirar como les va a todas desde un solo sitio.
 *
 * Para que sirve: los caches estan repartidos por el codigo, cada uno donde se usa. Sin un
 * listado, la pantalla de ajustes tendria que conocerlos uno por uno y nombrarlos a mano,
 * y un cache nuevo no aparecería hasta que alguien se acordara de ir a añadirlo.
 *
 *
 * POR QUE ArrayMap Y NO HashMap
 *
 * Las dos estructuras hacen lo mismo de cara a quien las usa: guardar valores por clave.
 * La diferencia esta en como lo hacen por dentro.
 *
 * - Un **HashMap** tiene un arreglo de "cubetas" y, por cada entrada, crea un objeto `Node`
 *   que guarda la clave, el valor, el hash y un enlace al siguiente. Ese objeto por entrada
 *   es el coste: ocupa memoria aparte de los datos, y el recolector de basura tiene que
 *   seguirlo. A cambio, buscar es O(1): se calcula el hash y se va directo a la cubeta.
 *
 * - Un **ArrayMap** guarda dos arreglos planos: uno de enteros con los hashes, ordenado, y
 *   otro de objetos con las claves y los valores intercalados. No crea ningun objeto por
 *   entrada. Buscar es una busqueda binaria sobre el arreglo de hashes, o sea O(log n), e
 *   insertar o borrar obliga a correr los elementos de sitio, O(n).
 *
 * La decision depende del tamaño, y aqui el tamaño se conoce: tres o cuatro caches. Con
 * tantas entradas, la diferencia entre O(1) y O(log n) son dos o tres comparaciones de
 * enteros, que no se miden; mientras que ahorrar un objeto por entrada si se nota. Es
 * exactamente el caso para el que Android recomienda ArrayMap: mapas pequeños, de cientos
 * de entradas o menos. Con miles de entradas la recomendacion se invierte, porque el O(n)
 * de insertar deja de ser gratis.
 *
 *
 * POR QUE androidx.collection.ArrayMap Y NO android.util.ArrayMap
 *
 * Hay dos clases con el mismo nombre. La de `android.util` es parte del sistema, asi que no
 * existe cuando los tests corren en el computador: usarla obligaria a probar esto en un
 * emulador. La de `androidx.collection` es Java puro y viene empaquetada con la app, asi
 * que funciona igual en el telefono y en los tests, y se comporta igual en todas las
 * versiones de Android en vez de depender de la del dispositivo.
 *
 *
 * POR QUE NO SparseArray
 *
 * SparseArray es la tercera opcion de la familia y aqui no sirve, por una razon concreta:
 * sus claves son enteros primitivos. Su ventaja es justamente esa, que evita envolver cada
 * clave en un objeto `Integer` como haria un `Map<Int, V>`. Pero las claves de este listado
 * son los nombres de los caches, que son texto. Para usar SparseArray habria que inventarle
 * un numero a cada cache y llevar aparte la correspondencia entre numero y nombre, o sea
 * añadir una estructura para poder usar la estructura. Elegirla aqui seria confundir "la
 * mas eficiente" con "la que encaja".
 */
object CacheRegistry {

    private val caches = ArrayMap<String, CacheStats>()

    /**
     * Apunta un cache en el listado.
     *
     * Lo llama cada cache al crearse, de modo que el listado se arma solo y no hay que
     * mantener una lista escrita a mano que se quede vieja.
     *
     * `@Synchronized` porque ArrayMap, igual que HashMap, no es seguro entre hilos, y los
     * caches pueden crearse desde hilos distintos la primera vez que alguien los usa.
     */
    @Synchronized
    fun register(name: String, stats: CacheStats) {
        caches[name] = stats
    }

    /**
     * Como va cada cache en este momento.
     *
     * Se devuelven las fotos y no los contadores vivos para que quien las lea vea numeros
     * coherentes entre si, y para que no pueda modificarlos desde fuera.
     */
    @Synchronized
    fun snapshots(): List<CacheStats.Snapshot> = caches.values.map { it.snapshot() }

    /** Pone a cero los contadores de todos los caches. Lo usa el boton de los ajustes. */
    @Synchronized
    fun resetAll() {
        caches.values.forEach { it.reset() }
    }

    /**
     * Guarda los contadores de todos los caches para que sobrevivan al cierre de la app.
     *
     * Se llama de vez en cuando y no en cada acierto: el sentido de los contadores es dar
     * una tasa de varias sesiones de uso real, y escribir en disco en cada consulta al
     * cache anularia el ahorro que el cache consigue.
     */
    @Synchronized
    fun persistAll() {
        caches.values.forEach { it.persist() }
    }

    /** Vacia el listado. Solo lo usan los tests, para no arrastrar estado entre ellos. */
    @Synchronized
    fun clear() {
        caches.clear()
    }
}
