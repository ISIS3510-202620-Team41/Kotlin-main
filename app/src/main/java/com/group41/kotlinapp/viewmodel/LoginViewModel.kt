package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.userMessage
import com.group41.kotlinapp.repository.ApiAuthRepository
import com.group41.kotlinapp.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Todo lo que la pantalla de login necesita para dibujarse */
data class LoginUiState(
    val loading: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val error: String? = null,
    /** Login correcto: la pantalla navega al inicio */
    val loggedIn: Boolean = false
)

class LoginViewModel(
    private val auth: AuthRepository = ApiAuthRepository(),
    /**
     * Si hay conexion, como una funcion y no llamando directamente a ApiClient.
     *
     * Hace falta por lo mismo que en ActivityRepository: `ApiClient.isOnline()` necesita el
     * contexto de Android, que en una prueba de escritorio no existe. Con la comprobacion
     * detras de una funcion, el test pasa la suya y puede probar los dos casos, con red y
     * sin ella, sin emulador.
     */
    private val online: () -> Boolean = { ApiClient.isOnline() }
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun hasSession() = auth.hasSession()

    fun login(email: String, password: String) {
        if (_state.value.loading) return

        val cleanEmail = email.trim()
        val emailError = Validation.email(cleanEmail)
        val passwordError = if (password.isEmpty()) "Escribe tu contraseña" else null
        _state.update { LoginUiState(emailError = emailError, passwordError = passwordError) }
        if (emailError != null || passwordError != null) return

        // Sin internet no se intenta la peticion: iniciar sesion necesita al servidor,
        // porque es el unico que sabe si la contrasena es correcta. Intentarlo igual
        // dejaria al usuario diez segundos mirando el boton hasta que se agote el tiempo
        // de espera, para acabar en un error generico de red.
        //
        // Lo que si funciona sin internet es entrar con la sesion que ya estaba guardada,
        // y por eso el mensaje lo dice: el usuario que ya habia entrado antes no tiene que
        // escribir nada, y quien nunca entro sabe que lo que le falta es conexion y no que
        // se equivoco de contrasena.
        if (!online()) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "Sin conexion. Si ya habias iniciado sesion en este telefono, " +
                        "cierra y vuelve a abrir la app para entrar sin internet."
                )
            }
            return
        }

        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                auth.login(cleanEmail, password)
                _state.update { it.copy(loading = false, loggedIn = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when {
                    e is HttpException && e.code() == 401 -> "Correo o contraseña incorrectos"
                    else -> e.userMessage()
                }
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
