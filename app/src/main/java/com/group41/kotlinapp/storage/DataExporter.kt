package com.group41.kotlinapp.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.dao.SearchHistoryDao
import com.group41.kotlinapp.data.local.dao.SyncLogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escribe los datos del usuario en archivos dentro del almacenamiento privado de la app.
 *
 * Por que archivos, si ya hay una base de datos:
 *
 * - Un archivo se puede sacar de la app. La base de datos vive encerrada en el telefono y
 *   solo la entiende esta app; un JSON se puede mandar por correo, guardar en la nube o
 *   abrir en un computador. Eso es lo que permite que el usuario se lleve sus datos.
 *
 * - Un log es una lista que solo crece y que se lee de arriba abajo. Meter eso en SQLite
 *   funciona, pero pagar el costo de un motor de base de datos para escribir una linea al
 *   final de un texto no tiene sentido.
 *
 * Los dos archivos van en `filesDir` y no en `cacheDir`. La diferencia importa: Android
 * borra `cacheDir` cuando necesita espacio, sin avisar. Un export que el usuario pidio y
 * que todavia no ha compartido no puede desaparecer solo, y el log de sincronizacion
 * tampoco, porque su utilidad es justamente ser lo que queda cuando algo salio mal.
 */
object DataExporter {

    private const val EXPORTS_DIRECTORY = "exports"
    private const val LOGS_DIRECTORY = "logs"
    private const val SYNC_LOG_FILE = "sync.log"

    /**
     * Tope del archivo de log.
     *
     * Un log sin tope es una fuga de disco lenta: crece unos bytes por cada intento de
     * sincronizacion y nada lo vacia nunca. 256 KB son decenas de miles de lineas, mucho
     * mas de lo que alguien va a revisar, y siguen siendo una fraccion insignificante del
     * almacenamiento de un telefono.
     */
    private const val MAX_LOG_BYTES = 256L * 1024

    /**
     * Escribe un JSON con lo que el usuario tiene guardado en este telefono y devuelve
     * el archivo.
     *
     * Corre en [Dispatchers.IO] porque lee de la base de datos y escribe en disco: las dos
     * cosas son esperas, no calculos.
     */
    suspend fun exportUserData(
        context: Context,
        userId: String,
        activityDao: ActivityDao,
        searchHistoryDao: SearchHistoryDao,
        syncLogDao: SyncLogDao
    ): File = withContext(Dispatchers.IO) {

        val activities = activityDao.getAllActivities().first()
        val searches = searchHistoryDao.recentSearches(userId, limit = 100).first()
        val outcomes = syncLogDao.outcomeTotals(userId)

        val document = JSONObject().apply {
            put("exportedAt", isoTimestamp(System.currentTimeMillis()))
            put("userId", userId)

            put("activities", JSONArray().apply {
                activities.forEach { activity ->
                    put(
                        JSONObject().apply {
                            put("id", activity.id)
                            put("name", activity.name)
                            put("category", activity.category)
                            put("joined", activity.joined)
                            put("latitude", activity.latitude)
                            put("longitude", activity.longitude)
                            // Es el campo que dice si la inscripcion todavia no ha llegado
                            // al servidor. En un export le sirve al usuario de constancia.
                            put("pendingSync", activity.pendingSync)
                        }
                    )
                }
            })

            put("searchHistory", JSONArray().apply {
                searches.forEach { search ->
                    put(
                        JSONObject().apply {
                            put("searchedAt", isoTimestamp(search.searchedAt))
                            put("latitude", search.latitude)
                            put("longitude", search.longitude)
                            put("radiusKm", search.radiusKm)
                            put("resultCount", search.resultCount)
                            put("wasOffline", search.wasOffline)
                        }
                    )
                }
            })

            put("syncTotals", JSONObject().apply {
                outcomes.forEach { total -> put(total.outcome, total.total) }
            })
        }

        val directory = File(context.filesDir, EXPORTS_DIRECTORY).apply { mkdirs() }
        val target = File(directory, "mis_datos_" + fileTimestamp() + ".json")

        writeAtomically(target, document.toString(2))
        target
    }

    /**
     * Escribe el contenido sin dejar nunca un archivo a medias.
     *
     * El truco esta en no escribir directamente sobre el archivo final. Se escribe en uno
     * temporal y, cuando ya esta completo, se renombra. Renombrar dentro del mismo sistema
     * de archivos es una operacion atomica: o pasa del todo o no pasa.
     *
     * Sin esto, si el proceso muriera a mitad de la escritura (el usuario cierra la app, o
     * Android la mata por falta de memoria), quedaria un JSON cortado por la mitad. El
     * usuario lo abriria y veria basura, sin forma de saber que esta incompleto.
     */
    private fun writeAtomically(target: File, content: String) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(content)

        // Si quedo un archivo del mismo nombre de un intento anterior, hay que quitarlo:
        // renameTo no sobreescribe en todos los sistemas de archivos.
        if (target.exists()) target.delete()

        if (!temporary.renameTo(target)) {
            // El renombrado fallo. Se limpia el temporal para no dejar basura y se avisa,
            // en vez de dejar creer que el export existe.
            temporary.delete()
            throw IOException("No se pudo guardar el archivo de export")
        }
    }

    /**
     * Agrega una linea al archivo de log, recortandolo si ya es muy grande.
     *
     * La rotacion es por tamaño y no por fecha porque lo que hay que acotar es el disco.
     * Cuando se pasa del tope, se tira la mitad mas vieja: se conserva lo reciente, que es
     * lo que sirve para entender un problema que esta pasando ahora.
     */
    suspend fun appendToSyncLog(context: Context, line: String) = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, LOGS_DIRECTORY).apply { mkdirs() }
        val file = File(directory, SYNC_LOG_FILE)

        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val lines = file.readLines()
            // Se reescribe el archivo con la mitad reciente. Es una escritura completa y no
            // un append, asi que pasa por la escritura atomica: un log truncado a medias
            // seria peor que un log grande.
            writeAtomically(file, lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
        }

        file.appendText(isoTimestamp(System.currentTimeMillis()) + " " + line + "\n")
    }

    /**
     * El content:// para compartir un archivo exportado con otra app.
     *
     * Hace falta FileProvider y no vale pasar la ruta del archivo: desde Android 7, mandar
     * un `file://` a otra app lanza FileUriExposedException. La razon es que la otra app no
     * tiene permiso para leer el almacenamiento privado de esta. Un `content://` le da
     * permiso solo sobre ese archivo y solo mientras dure la operacion.
     */
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** Cuanto ocupan los exports, en bytes. Se muestra en los ajustes. */
    fun exportsSizeBytes(context: Context): Long =
        File(context.filesDir, EXPORTS_DIRECTORY)
            .listFiles()
            ?.sumOf { it.length() }
            ?: 0L

    /** Borra los archivos exportados. El log no se toca: sirve para diagnosticar. */
    fun clearExports(context: Context) {
        File(context.filesDir, EXPORTS_DIRECTORY).listFiles()?.forEach { it.delete() }
    }

    /**
     * Fecha legible y ordenable para el contenido de los archivos.
     *
     * Locale.US a proposito: el formato de fecha de un archivo tecnico no debe cambiar
     * segun el idioma del telefono, porque entonces dos exports del mismo usuario hechos
     * en dos telefonos distintos no se podrian comparar.
     */
    private fun isoTimestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    /** Fecha para el nombre del archivo. Sin dos puntos, que no son validos en un nombre. */
    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
