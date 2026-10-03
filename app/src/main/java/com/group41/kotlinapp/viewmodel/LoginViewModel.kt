package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val auth: AuthRepository = ApiAuthRepository()
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
