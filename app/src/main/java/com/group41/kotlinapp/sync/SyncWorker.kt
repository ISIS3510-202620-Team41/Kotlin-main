package com.group41.kotlinapp.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.group41.kotlinapp.App
import com.group41.kotlinapp.AppPreferences
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.dao.SyncLogDao
import com.group41.kotlinapp.data.local.entity.SyncLogEntity
import com.group41.kotlinapp.data.local.entity.SyncOutcome
import com.group41.kotlinapp.storage.DataExporter
import com.group41.kotlinapp.cache.DealsMemoryCache
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.model.RedeemDealPayload
import com.group41.kotlinapp.network.DealInteractionDto
import com.group41.kotlinapp.model.PendingAction
import com.group41.kotlinapp.model.PendingActionType
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.repository.PendingActionRepository
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Vacía el buzón de acciones pendientes: manda al backend lo que el usuario hizo sin internet.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()

        val userId = ApiClient.tokens.userId ?: return Result.success()

        val queue = PendingActionRepository(app.database)
        val pending = queue.pendingFor(userId)
        if (pending.isEmpty()) return Result.success()

        var shouldRetryLater = false
        var allActionsSucceeded = true

        val syncLog = app.database.syncLogDao()

        for (action in pending) {
            // If the account changes mid-run, leave the remaining actions queued
            // until their owner signs in again.
            if (ApiClient.tokens.userId != userId) return Result.success()
            if (action.type == PendingActionType.JOIN_ACTIVITY || action.type == PendingActionType.CANCEL_ENROLLMENT) {
                // The repository confirms or restores the enrollment when it removes
                // the action. WorkManager retries temporary failures.
                try {
                    if (!queue.syncEnrollment(action) { ApiClient.tokens.userId == userId }) {
                        allActionsSucceeded = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    allActionsSucceeded = false
                    shouldRetryLater = true
                }
                continue
            }
            if (action.type == PendingActionType.REDEEM_DEAL) {
                val redeemOutcome = sendRedeemDeal(action, app)
                if (redeemOutcome != Outcome.DONE) allActionsSucceeded = false
                when (redeemOutcome) {
                    Outcome.DONE -> queue.markSynced(action)
                    Outcome.REJECTED, Outcome.UNPROCESSABLE -> queue.discard(action)
                    Outcome.RETRY_LATER -> {
                        if (queue.recordFailure(action)) shouldRetryLater = true
                    }
                    Outcome.KEEP_PENDING -> Unit
                }
                continue
            }

            val outcome = sendCreate(action, queue)
            // A rejected or discarded action is still a failed synchronization, even
            // when removing it leaves the queue empty. Do not report that as success.
            if (outcome != Outcome.DONE) allActionsSucceeded = false
            when (outcome) {
                Outcome.DONE -> queue.markSynced(action)
                Outcome.REJECTED -> queue.discard(action)
                Outcome.RETRY_LATER -> {
                    if (queue.recordFailure(action)) shouldRetryLater = true
                }
                Outcome.UNPROCESSABLE -> queue.discard(action)
                // Retain the row and JPEG without requesting an automatic retry.
                Outcome.KEEP_PENDING -> Unit
            }
        }

        // Read the actual database rows: pendingFor() skips malformed or unknown actions,
        // and new actions may have arrived while this worker was sending the original list.
        // An empty initial queue returns above because no synchronization took place.
        if (allActionsSucceeded && app.database.pendingActionDao().getPendingActions(userId).isEmpty() &&
            ApiClient.tokens.userId == userId) {
            AppPreferences(app).saveLastSuccessfulSync(System.currentTimeMillis(), expectedUserId = userId)
        }

        return if (shouldRetryLater) Result.retry() else Result.success()
    }

    /**
     * Reconstruye la petición de creación desde el payload JSON y la envía al backend.
     */
    private suspend fun sendCreate(action: PendingAction, queue: PendingActionRepository): Outcome {
        return try {
            // After HTTP 201, the repository saves the backend ID and local photo path
            // in an UPLOAD_ACTIVITY_PHOTO row before uploading. Once saved, retries only upload.
            // An interruption before that save still needs backend idempotency.
            queue.syncActivityCreation(action)
            Outcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            creationFailureOutcome(e)
        }
    }

    /**
     * Manda al servidor una redencion de cupon que se hizo sin conexion.
     *
     * El contexto del descanso sale del payload y no se calcula ahora: el estudiante pudo
     * redimir dentro de un hueco entre clases y esta accion salir tres horas despues.
     * Calcularlo aqui registraria el descanso equivocado y la BQ-11 contaria mal.
     *
     * El reparto de errores es el mismo criterio que en las inscripciones: 4xx es
     * definitivo salvo el 401, que significa que el token expiro y no que el dato este
     * mal; 5xx y los fallos de red son temporales.
     */
    private suspend fun sendRedeemDeal(action: PendingAction, app: App): Outcome {
        val payload = RedeemDealPayload.fromJson(action.payload) ?: return Outcome.UNPROCESSABLE
        val deals = app.database.dealDao()

        return try {
            val response = ApiClient.api.redeemDeal(
                dealId = payload.dealId,
                context = DealInteractionDto(
                    dealId = payload.dealId,
                    type = "REDEEMED",
                    duringBreak = payload.duringBreak,
                    breakMinutesLeft = payload.breakMinutesLeft
                )
            )

            // Llego: se guarda el codigo y se quita la marca de pendiente. El usuario ya
            // puede ensenarlo en el local.
            deals.markRedeemed(payload.dealId, pendingSync = false, code = response.redemptionCode)
            DealsMemoryCache.invalidateAll()
            Outcome.DONE

        } catch (e: CancellationException) {
            throw e

        } catch (e: HttpException) {
            lastHttpCode = e.code()

            when {
                // El token expiro. El dato esta bien, asi que se reintenta: descartarlo
                // perderia una redencion valida por un problema de sesion.
                e.code() == 401 -> Outcome.RETRY_LATER

                e.code() in 400..499 -> {
                    // Rechazo definitivo: el cupon vencio o el comercio lo retiro. Se
                    // revierte en el telefono, porque dejarlo como redimido le prometeria
                    // al usuario un descuento que no le van a aceptar.
                    deals.revertRedemption(payload.dealId)
                    DealsMemoryCache.invalidateAll()
                    Outcome.REJECTED
                }

                else -> Outcome.RETRY_LATER
            }

        } catch (e: IOException) {
            Outcome.RETRY_LATER
        }
    }

    /** Possible results, including retained actions that need correction before retrying. */
    internal enum class Outcome {
        DONE,
        REJECTED,
        RETRY_LATER,
        UNPROCESSABLE,
        KEEP_PENDING
    }

    /**
     * El codigo HTTP del ultimo intento, si el servidor llego a contestar.
     *
     * Es una propiedad del worker y no un valor devuelto porque [Outcome] solo dice que
     * hacer con la accion, y convertirlo en una clase con datos obligaria a tocar las dos
     * ramas del envio. El bucle de [doWork] es secuencial y cada worker atiende un unico
     * trabajo, asi que un solo campo basta: se limpia antes de cada intento y se lee justo
     * despues.
     */
    private var lastHttpCode: Int? = null

    /**
     * Deja constancia del intento en la tabla `sync_log` y en el archivo de log.
     *
     * Se escribe en los dos sitios porque sirven a lectores distintos:
     *
     * - La tabla la consulta la app: ordena por fecha, agrupa por resultado y se cruza con
     *   la cola. Es lo que alimenta la pantalla donde el usuario ve que esta pendiente.
     * - El archivo lo lee una persona. Se puede exportar y mandar, que es lo que hace falta
     *   cuando alguien reporta "mi inscripcion no llego" y hay que averiguar por que.
     *
     * Todo va dentro de un try: el registro es diagnostico, no una funcion de la app. Si
     * fallara al escribirlo, lo que no puede pasar es que la sincronizacion se caiga con
     * el, porque entonces la accion del usuario se quedaria sin mandar por no haber podido
     * anotar que se mando.
     */
    private suspend fun recordAttempt(
        app: App,
        syncLog: SyncLogDao,
        action: PendingAction,
        outcome: Outcome
    ) {
        // KEEP_PENDING no se registra: no hubo intento contra el servidor, la accion
        // simplemente se dejo quieta. Anotarlo llenaria el log de lineas sin informacion.
        if (outcome == Outcome.KEEP_PENDING) return

        val resultado = when (outcome) {
            Outcome.DONE -> SyncOutcome.SUCCESS
            Outcome.RETRY_LATER -> SyncOutcome.RETRY
            // Un payload que no se puede leer es un rechazo definitivo igual que un 4xx:
            // en los dos casos la accion se saca de la cola y no se vuelve a intentar.
            Outcome.REJECTED, Outcome.UNPROCESSABLE -> SyncOutcome.REJECTED
            Outcome.KEEP_PENDING -> return
        }

        try {
            syncLog.insert(
                SyncLogEntity(
                    actionId = action.id,
                    userId = action.userId,
                    actionType = action.type.name,
                    outcome = resultado,
                    httpCode = lastHttpCode,
                    attemptedAt = System.currentTimeMillis()
                )
            )

            DataExporter.appendToSyncLog(
                context = app,
                line = "${action.type.name} ${action.id} -> $resultado" +
                    (lastHttpCode?.let { " (HTTP $it)" } ?: "")
            )
        } catch (error: Exception) {
            Log.e("SyncWorker", "No se pudo registrar el intento de sincronizacion", error)
        }
    }

    companion object {
        /** Retry known temporary failures; preserve validation failures and uncertain data. */
        internal fun creationFailureOutcome(error: Exception): Outcome = when (error) {
            is HttpException -> when (error.code()) {
                // Authentication may recover after refresh; timeouts/rate limits are temporary.
                401, 408, 425, 429 -> Outcome.RETRY_LATER
                in 500..599 -> Outcome.RETRY_LATER
                // Includes 400/422. Keep the payload and JPEG for a later explicit sync
                // after correction, without starting an endless WorkManager retry loop.
                else -> Outcome.KEEP_PENDING
            }
            is IOException -> Outcome.RETRY_LATER
            // Missing IDs/files and parsing failures cannot safely justify discarding data.
            else -> Outcome.KEEP_PENDING
        }
    }
}
