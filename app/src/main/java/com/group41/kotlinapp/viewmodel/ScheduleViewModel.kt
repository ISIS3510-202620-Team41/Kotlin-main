package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.network.SyncResult
import com.group41.kotlinapp.network.isUnauthorized
import com.group41.kotlinapp.network.userMessage
import com.group41.kotlinapp.repository.ApiAuthRepository
import com.group41.kotlinapp.repository.ApiScheduleRepository
import com.group41.kotlinapp.repository.AuthRepository
import com.group41.kotlinapp.repository.ScheduleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScheduleUiState(
    val googleConnected: Boolean = false,
    /** Importando eventos de Google: el backend tarda unos segundos */
    val syncing: Boolean = false,
    /** Ventanas libres de hoy; null mientras no se han pedido */
    val freeSlots: List<FreeSlotDto>? = null,
    val sessionExpired: Boolean = false
)

sealed interface ScheduleEvent {
    data class Message(val text: String) : ScheduleEvent

    /** El token guardado ya no sirve (se revocó el acceso): hay que pedir permiso otra vez */
    data object NeedsConsent : ScheduleEvent
}

class ScheduleViewModel(
    private val schedule: ScheduleRepository = ApiScheduleRepository(),
    private val auth: AuthRepository = ApiAuthRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleUiState(googleConnected = schedule.googleConnected()))
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    private val _events = Channel<ScheduleEvent>(Channel.BUFFERED)
    val events: Flow<ScheduleEvent> = _events.receiveAsFlow()

    /** Código de un solo uso que entrega Google tras el consentimiento */
    fun onAuthCode(code: String) = sync(resync = false) { schedule.connectGoogle(code) }

    /** Ya conectado: vuelve a importar sin pedir nada al usuario */
    fun resync() = sync(resync = true) { schedule.resyncGoogle() }

    /** Sin `force` no repite si ya las tiene (ej. tras rotar la pantalla) */
    fun loadFreeSlots(force: Boolean = false) {
        if (!force && _state.value.freeSlots != null) return
        viewModelScope.launch {
            try {
                val slots = schedule.freeSlots(today())
                _state.update { it.copy(freeSlots = slots) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Es información secundaria: sin red se queda lo que había
                if (e.isUnauthorized()) expireSession()
            }
        }
    }

    private fun sync(resync: Boolean, action: suspend () -> SyncResult) {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            try {
                val result = action()
                _state.update { it.copy(googleConnected = true, syncing = false) }
                _events.send(ScheduleEvent.Message(summary(result)))
                loadFreeSlots(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(syncing = false) }
                handleSyncError(e, resync)
            }
        }
    }

    private suspend fun handleSyncError(e: Exception, resync: Boolean) {
        val code = (e as? HttpException)?.code()
        when {
            e.isUnauthorized() -> expireSession()
            // 409: Google revocó el acceso. 400 al re-sincronizar: nunca se conectó
            resync && (code == 409 || code == 400) -> {
                schedule.setGoogleConnected(false)
                _state.update { it.copy(googleConnected = false) }
                _events.send(ScheduleEvent.NeedsConsent)
            }
            code == 409 -> {
                schedule.setGoogleConnected(false)
                _state.update { it.copy(googleConnected = false) }
                _events.send(ScheduleEvent.Message(e.userMessage()))
            }
            else -> _events.send(ScheduleEvent.Message(e.userMessage()))
        }
    }

    private fun expireSession() {
        auth.clearSession()
        _state.update { it.copy(sessionExpired = true) }
    }

    private fun summary(r: SyncResult): String = when {
        r.imported == 0 -> "Google Calendar conectado, pero no se encontraron eventos"
        else -> {
            val events = if (r.imported == 1) "1 evento" else "${r.imported} eventos"
            val calendars = if (r.calendars == 1) "1 calendario" else "${r.calendars} calendarios"
            "Se importaron $events de $calendars"
        }
    }

    /** Día del dispositivo; el endpoint recibe la misma zona en `tz` */
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}