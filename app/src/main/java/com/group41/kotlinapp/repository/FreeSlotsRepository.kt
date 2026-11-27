package com.group41.kotlinapp.repository

import android.util.Log
import com.group41.kotlinapp.analytics.BreakDetector
import com.group41.kotlinapp.analytics.BreakState
import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.storage.FastKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Los huecos entre clases del estudiante, guardados en el telefono.
 *
 * Es la pieza que falta para la BQ-11: el detector sabe interpretar los huecos, pero
 * alguien tiene que conseguirlos y tenerlos a mano. Y "a mano" aqui significa algo
 * concreto: cada vez que un cupon aparece en pantalla hay que marcar el evento con si el
 * estudiante estaba o no en un descanso. Si eso preguntara a la red, mostrar una lista de
 * diez cupones dispararia diez peticiones, y sin internet no se marcaria ninguno: la BQ
 * perderia justo los datos del modo sin conexion, que es donde la app presume de servir.
 *
 * Por eso los huecos se piden una vez y se guardan.
 *
 * **La caducidad es el dia, no un temporizador.** Los huecos describen una fecha concreta,
 * asi que dejan de valer cuando cambia el dia, no cuando pasan N minutos. Eso hace que no
 * haga falta un TTL: basta con guardar la fecha junto a los datos y comparar. Es mas
 * simple y es mas correcto, porque un TTL de doce horas serviria huecos de ayer a las dos
 * de la madrugada.
 *
 * Van a MMKV y no a Room porque es un documento pequeno que se reescribe entero una vez al
 * dia. No hay nada que consultar ni relacionar: se lee completo o no se lee.
 */
class FreeSlotsRepository(
    private val schedule: ScheduleRepository = ApiScheduleRepository(),
    private val detector: BreakDetector = BreakDetector(),
    /**
     * Donde se guardan los huecos, detras de una interfaz.
     *
     * Por la misma razon que `online` en LoginViewModel: MMKV necesita Android, y asi
     * estas reglas (cuando se refresca, que pasa si falla la red) se pueden probar en el
     * computador sin emulador. La version de verdad esta justo debajo.
     */
    private val store: SlotStore = MmkvSlotStore,
    private val today: () -> String = { LocalDate.now().toString() },
    /**
     * Donde se avisa de un fallo al cargar.
     *
     * Tambien entra por el constructor, y por el mismo motivo: `android.util.Log` no
     * existe fuera de un dispositivo, asi que una prueba de escritorio que pasara por
     * aqui reventaria con "Method w in android.util.Log not mocked". Pasarlo como
     * funcion es preferible a activar `returnDefaultValues` en Gradle, que haria que
     * TODAS las llamadas a Android devolvieran cero o null en silencio y podria tapar
     * fallos de verdad en otros tests.
     */
    private val logWarning: (String, Throwable) -> Unit = { message, error ->
        Log.w("FreeSlotsRepository", message, error)
    }
) {

    /**
     * En que momento del dia esta el estudiante ahora mismo.
     *
     * Es la funcion que usan los eventos de analitica. No lanza nunca y no bloquea: si no
     * hay nada guardado y la red falla, responde que no se sabe. Un fallo al cargar el
     * horario no puede impedir que se registre el evento del cupon, porque entonces se
     * perderian tambien los eventos, y no solo su contexto.
     */
    suspend fun currentBreakState(): BreakState = detector.evaluate(slotsForToday())

    /**
     * Los huecos de hoy: de lo guardado si sirve, y si no, del servidor.
     *
     * Devuelve null cuando no se sabe nada, que es distinto de devolver una lista vacia.
     * El detector trata esos dos casos de forma opuesta y de eso depende que la BQ no
     * quede sesgada.
     */
    suspend fun slotsForToday(): List<FreeSlotDto>? {
        val date = today()

        // Lo guardado hoy se usa tal cual. Es el camino normal y no toca la red.
        store.read()?.let { stored ->
            if (stored.date == date) return stored.slots
        }

        return refresh(date)
    }

    /**
     * Pide los huecos al servidor y los guarda.
     *
     * Devuelve null si no se pudieron conseguir. Se devuelve null y no una lista vacia a
     * proposito: una lista vacia significaria "hoy no tienes huecos", que es una
     * afirmacion, y aqui lo cierto es que no se sabe.
     */
    suspend fun refresh(date: String = today()): List<FreeSlotDto>? = withContext(Dispatchers.IO) {
        try {
            val slots = schedule.freeSlots(date)
            store.write(StoredSlots(date, slots))
            slots
        } catch (error: Exception) {
            // Sin red, o sin horario importado. No es un fallo que mostrar al usuario: la
            // pantalla de cupones funciona igual, solo que sus eventos iran marcados como
            // "no se sabe si era un descanso".
            logWarning("No se pudieron cargar los huecos de $date", error)

            // Si hay algo guardado de otro dia, no sirve: describe fechas que ya pasaron.
            null
        }
    }
}

/** Los huecos de un dia concreto, tal como quedan guardados. */
data class StoredSlots(val date: String, val slots: List<FreeSlotDto>)

/**
 * Donde viven los huecos guardados.
 *
 * Existe como interfaz solo para poder probar el repositorio sin Android.
 */
interface SlotStore {
    fun read(): StoredSlots?
    fun write(value: StoredSlots)
}

/**
 * La implementacion de verdad, sobre MMKV.
 *
 * Guarda un unico documento JSON con la fecha dentro. La fecha va en el mismo documento y
 * no en otra clave para que no puedan quedar desparejados: si se guardaran aparte y el
 * proceso muriera entre las dos escrituras, se leerian huecos de ayer con la fecha de hoy,
 * que es el peor resultado posible, porque parece correcto.
 */
object MmkvSlotStore : SlotStore {

    private const val KEY = "schedule.freeSlots"

    override fun read(): StoredSlots? {
        val raw = FastKeyValueStore.getString(KEY) ?: return null

        return runCatching {
            val document = JSONObject(raw)
            val array = document.getJSONArray("slots")
            val slots = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                FreeSlotDto(start = item.getString("start"), end = item.getString("end"))
            }
            StoredSlots(date = document.getString("date"), slots = slots)
        }.getOrElse {
            // Un documento corrupto se descarta y se vuelve a pedir, en vez de propagar el
            // error. Lo guardado es una copia de conveniencia: siempre se puede rehacer.
            FastKeyValueStore.remove(KEY)
            null
        }
    }

    override fun write(value: StoredSlots) {
        val array = JSONArray()
        value.slots.forEach { slot ->
            array.put(JSONObject().put("start", slot.start).put("end", slot.end))
        }

        val document = JSONObject()
            .put("date", value.date)
            .put("slots", array)

        FastKeyValueStore.putString(KEY, document.toString())
    }
}
