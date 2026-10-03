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

data class RegisterUiState(
    val loading: Boolean = false,
    val nameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val error: String? = null,
    /** Registro correcto: ya hay sesión y la pantalla navega al inicio */
    val registered: Boolean = false
)

class RegisterViewModel(
    private val auth: AuthRepository = ApiAuthRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    fun register(name: String, email: String, password: String) {
        if (_state.value.loading) return

        val cleanName = name.trim()
        val cleanEmail = email.trim()
        val errors = RegisterUiState(
            nameError = Validation.name(cleanName),
            emailError = Validation.email(cleanEmail),
            passwordError = Validation.newPassword(password)
        )
        _state.update { errors }
        if (errors.nameError != null || errors.emailError != null || errors.passwordError != null) return

        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                auth.register(cleanName, cleanEmail, password)
                _state.update { it.copy(loading = false, registered = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (e is HttpException && e.code() == 409) {
                        it.copy(loading = false, emailError = "Este correo ya tiene un perfil")
                    } else {
                        it.copy(loading = false, error = e.userMessage())
                    }
                }
            }
        }
    }
}
