package com.group41.kotlinapp.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Le encarga a Android que vacie la cola offline cuando pueda.
 *
 * Esta clase es el unico sitio de la app que le habla a WorkManager. El resto del codigo
 * solo llama a `schedule(context)` y se olvida: no tiene que saber nada de condiciones ni
 * de reintentos.
 *
 * La pieza clave esta en la restriccion de abajo. Es la que hace que todo esto funcione
 * aunque el usuario cierre la app: no somos nosotros quienes vigilamos la conexion, es
 * Android quien guarda la tarea y la despierta cuando aparece internet.
 */
object SyncScheduler {

    /**
     * Nombre con el que Android identifica esta tarea.
     *
     * Hace falta porque se pide trabajo "unico": aunque se llame a schedule() cinco veces
     * seguidas, Android entiende que todas se refieren a la misma tarea y no lanza cinco
     * sincronizaciones en paralelo, que es la forma mas facil de mandar lo mismo repetido.
     */
    private const val WORK_NAME = "offline-sync"

    /**
     * Pide que la cola se sincronice en cuanto sea posible.
     *
     * Se llama justo despues de encolar algo, y tambien al iniciar sesion, por si quedaron
     * acciones de la vez anterior.
     */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    // Esta linea es el corazon del issue. No dice "corre ahora", dice
                    // "corre cuando haya internet". Si el telefono esta en modo avion, la
                    // tarea se queda dormida; en el momento en que vuelve la conexion,
                    // Android la despierta solo. Por eso la inscripcion se manda aunque el
                    // usuario haya cerrado la app hace horas.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                // Si el intento falla, Android espera antes de volver a intentar, y cada vez
                // espera mas: 10 s, 20 s, 40 s, 80 s... Se llama espera exponencial.
                //
                // Se eligio sobre la alternativa (esperar siempre lo mismo) porque cuando un
                // servidor esta caido, mil telefonos reintentando cada 10 segundos lo tumban
                // justo cuando intenta levantarse. Espaciando los intentos, el servidor tiene
                // aire para recuperarse y el telefono gasta menos bateria.
                BackoffPolicy.EXPONENTIAL,
                10, TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            // APPEND_OR_REPLACE resuelve un caso concreto: que el usuario encole una segunda
            // inscripcion mientras la primera sincronizacion ya esta corriendo. Esa tarea en
            // curso leyo la cola al empezar y no va a ver la nueva accion.
            //
            // Con esta politica, la peticion nueva se pone en fila detras de la que corre, y
            // cuando termine arrancara otra pasada que si la vera. Si no hubiera ninguna
            // corriendo, arranca de inmediato.
            //
            // KEEP habria descartado la peticion nueva y esa inscripcion se habria quedado
            // esperando sin que nadie la mandara.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}
