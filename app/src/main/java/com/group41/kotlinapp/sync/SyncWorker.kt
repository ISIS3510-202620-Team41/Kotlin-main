package com.group41.kotlinapp.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.group41.kotlinapp.App
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.model.PendingAction
import com.group41.kotlinapp.model.PendingActionType
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.repository.PendingActionRepository
import retrofit2.HttpException
import java.io.IOException

/**
 * Vacia el buzon de acciones pendientes: manda al backend lo que el usuario hizo sin internet.
 *
 * ¿Que es un Worker y por que no hacer esto en la pantalla?
 *
 * Porque la pantalla se muere. Si el usuario se inscribe sin internet y cierra la app, no
 * queda nadie esperando a que vuelva la conexion. Un Worker es una tarea que se le encarga
 * a Android: el sistema la guarda y la ejecuta cuando se cumplan las condiciones que
 * pedimos, aunque la app este cerrada y aunque el telefono se haya reiniciado.
 *
 * Es `CoroutineWorker` y no `Worker` a secas porque el trabajo que hace es esperar respuestas
 * de la red, y las corrutinas permiten esperar sin bloquear un hilo completo mientras tanto.
 *
 * La condicion de "cuando ejecutarlo" no se decide aqui sino en SyncScheduler, que le pide a
 * Android que solo lo corra cuando haya internet.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()

        // Sin sesion no hay a nombre de quien mandar nada. Puede pasar si el usuario cerro
        // sesion despues de encolar: se devuelve success (no failure) porque no es un error
        // ni algo que vaya a arreglarse reintentando; simplemente no hay nada que hacer.
        val userId = ApiClient.tokens.userId ?: return Result.success()

        val queue = PendingActionRepository(app.database.pendingActionDao())
        val activities = app.database.activityDao()
        val pending = queue.pendingFor(userId)
        if (pending.isEmpty()) return Result.success()

        // Se marca si hubo algun fallo pasajero para decidir al final si vale la pena
        // volver a intentarlo mas tarde.
        var shouldRetryLater = false

        for (action in pending) {
            when (send(action, activities)) {
                Outcome.DONE -> queue.markSynced(action)

                // El backend contesto que no. Reintentar daria la misma respuesta.
                Outcome.REJECTED -> queue.discard(action)

                // Fallo pasajero. Se anota el intento; si ya van demasiados, el repositorio
                // la descarta solo y devuelve false.
                Outcome.RETRY_LATER -> {
                    if (queue.recordFailure(action)) shouldRetryLater = true
                }

                // La fila esta corrupta o es de una version vieja: no se puede ejecutar nunca.
                Outcome.UNPROCESSABLE -> queue.discard(action)
            }
        }

        // `retry` le dice a WorkManager "no pude terminar, llamame otra vez". Android espera
        // cada vez un poco mas antes de reintentar (10 s, 20 s, 40 s...), lo que se llama
        // espera exponencial: evita machacar un servidor que ya esta en problemas.
        return if (shouldRetryLater) Result.retry() else Result.success()
    }

    /**
     * Intenta ejecutar una accion contra el backend y dice como le fue.
     */
    private suspend fun send(action: PendingAction, activities: ActivityDao): Outcome =
        when (action.type) {
            PendingActionType.JOIN_ACTIVITY -> sendJoin(action, activities)
        }

    private suspend fun sendJoin(action: PendingAction, activities: ActivityDao): Outcome {
        val payload = JoinPayload.fromJson(action.payload) ?: return Outcome.UNPROCESSABLE

        return try {
            ApiClient.api.join(
                activityId = payload.activityId,
                recommendationId = payload.recommendationId,
                freeTimeMinutes = payload.freeTimeMinutes
            )

            // El backend ya confirmo la inscripcion, asi que se quita el aviso de "pendiente"
            // de la actividad guardada. A partir de aqui la pantalla dira "Inscrito" a secas,
            // que ya es verdad.
            activities.getActivityById(payload.activityId)?.let { stored ->
                activities.insertActivity(stored.copy(joined = true, pendingSync = false))
            }
            Outcome.DONE

        } catch (e: HttpException) {
            // El servidor contesto, pero con un error. El numero dice si vale la pena insistir.
            //
            // 4xx = "lo que mandaste no se puede hacer": la actividad se lleno, ya estabas
            // inscrito, o dejo de existir. Mandarlo otra vez daria exactamente lo mismo.
            //
            // 401 es la excepcion dentro de los 4xx: significa que el token vencio, no que la
            // accion este mal. ApiClient intenta renovarlo solo; si aun asi llega aqui, se
            // reintenta mas tarde, cuando el usuario vuelva a entrar.
            //
            // 5xx = el servidor tuvo un problema suyo. Eso si suele arreglarse solo.
            when {
                e.code() == 401 -> Outcome.RETRY_LATER
                e.code() in 400..499 -> Outcome.REJECTED
                else -> Outcome.RETRY_LATER
            }

        } catch (e: IOException) {
            // Ni siquiera hubo respuesta: se cayo la conexion a mitad de camino, o el servidor
            // no esta accesible. Es el caso mas claro de "intentalo luego".
            Outcome.RETRY_LATER

        } catch (e: Exception) {
            // Cualquier otra cosa inesperada. Se trata como pasajero en vez de descartar la
            // accion: ante la duda, es mejor conservar lo que el usuario hizo. El contador de
            // reintentos evita que se quede dando vueltas para siempre.
            Outcome.RETRY_LATER
        }
    }

    /** Las cuatro cosas que pueden pasarle a una accion de la cola. */
    private enum class Outcome {
        /** Llego al backend. Se saca de la cola. */
        DONE,

        /** El backend dijo que no y no va a cambiar de opinion. Se saca de la cola. */
        REJECTED,

        /** Fallo pero puede funcionar despues. Se queda en la cola y suma un reintento. */
        RETRY_LATER,

        /** No se puede ni intentar: los datos guardados no sirven. Se saca de la cola. */
        UNPROCESSABLE
    }
}
