package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.AvatarException
import com.group41.kotlinapp.AvatarImage
import com.group41.kotlinapp.network.UserDto
import com.group41.kotlinapp.network.isUnauthorized
import com.group41.kotlinapp.network.userMessage
import com.group41.kotlinapp.repository.ApiAuthRepository
import com.group41.kotlinapp.repository.ApiUserRepository
import com.group41.kotlinapp.repository.AuthRepository
import com.group41.kotlinapp.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    /** null mientras llega de /api/users/me */
    val user: UserDto? = null,
    val loadingUser: Boolean = false,
    /** Subiendo o quitando la foto */
    val avatarBusy: Boolean = false,
    val saving: Boolean = false,
    val loggingOut: Boolean = false,
    val nameError: String? = null,
    val bioError: String? = null,
    val urlError: String? = null,
    /** El refresh token venció: hay que volver al login */
    val sessionExpired: Boolean = false,
    val loggedOut: Boolean = false
)

/** Cosas que pasan una sola vez (un aviso, cerrar el editor), no estado que se redibuja */
sealed interface ProfileEvent {
    data class Message(val text: String) : ProfileEvent
    data object Saved : ProfileEvent
}

/**
 * Perfil del usuario. Lo comparten ProfileActivity y EditProfileBottomSheet
 * (el editor lo pide con la Activity como dueña), así los dos ven el mismo
 * usuario y un cambio en el editor se ve al instante en el perfil.
 *
 * Al vivir en un ViewModel, el usuario y una subida de foto en curso
 * sobreviven a la rotación de la pantalla.
 */
class ProfileViewModel(
    private val users: UserRepository = ApiUserRepository(),
    private val auth: AuthRepository = ApiAuthRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events: Flow<ProfileEvent> = _events.receiveAsFlow()

    fun hasSession() = auth.hasSession()

    /**
     * Pide el usuario al backend. Sin `force` no repite si ya lo tiene o lo
     * está pidiendo: al rotar la pantalla no se vuelve a llamar a /me.
     */
    fun loadUser(force: Boolean = false) {
        val current = _state.value
        if (current.loadingUser || (!force && current.user != null)) return

        _state.update { it.copy(loadingUser = true) }
        viewModelScope.launch {
            try {
                val user = users.me()
                _state.update { it.copy(user = user, loadingUser = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sin red o backend caído: se queda con lo que tenía, sin cerrar la sesión
                _state.update { it.copy(loadingUser = false) }
                if (e.isUnauthorized()) expireSession()
            }
        }
    }

    /** `prepare` produce el JPEG; ver AvatarImage.fromUri */
    fun uploadAvatar(prepare: () -> ByteArray) {
        if (_state.value.avatarBusy) return
        _state.update { it.copy(avatarBusy = true, urlError = null) }
        viewModelScope.launch {
            try {
                val user = users.uploadAvatar(prepare)
                _state.update { it.copy(user = user, avatarBusy = false) }
                _events.send(ProfileEvent.Message("Foto actualizada"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(avatarBusy = false) }
                handleError(e)
            }
        }
    }

    fun uploadAvatarFromUrl(url: String) {
        val clean = url.trim()
        if (clean.isEmpty()) {
            _state.update { it.copy(urlError = "Pega la URL de una imagen") }
            return
        }
        uploadAvatar { AvatarImage.fromUrl(clean) }
    }

    fun removeAvatar() {
        if (_state.value.avatarBusy) return
        _state.update { it.copy(avatarBusy = true) }
        viewModelScope.launch {
            try {
                val user = users.deleteAvatar()
                _state.update { it.copy(user = user, avatarBusy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(avatarBusy = false) }
                handleError(e)
            }
        }
    }

    fun saveProfile(name: String, bio: String) {
        if (_state.value.saving) return

        val cleanName = name.trim()
        val cleanBio = bio.trim()
        // Un nombre vacío el backend lo ignora sin avisar, por eso se valida aquí
        val nameError = Validation.name(cleanName)
        val bioError = Validation.bio(cleanBio)
        _state.update { it.copy(nameError = nameError, bioError = bioError) }
        if (nameError != null || bioError != null) return

        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val user = users.updateProfile(cleanName, cleanBio)
                _state.update { it.copy(user = user, saving = false) }
                _events.send(ProfileEvent.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                handleError(e)
            }
        }
    }

    fun logout() {
        if (_state.value.loggingOut) return
        _state.update { it.copy(loggingOut = true) }
        viewModelScope.launch {
            auth.logout()
            _state.update { it.copy(loggingOut = false, loggedOut = true) }
        }
    }

    /**
     * Al abrir el editor: borra errores y avisos de una vez anterior. Si el
     * editor se cerró a mitad de un guardado, su "Saved" quedó pendiente y
     * cerraría el editor nuevo apenas abre.
     */
    fun onEditorOpened() {
        while (_events.tryReceive().isSuccess) {
            // descartar
        }
        _state.update { it.copy(nameError = null, bioError = null, urlError = null) }
    }

    private suspend fun handleError(e: Exception) {
        when {
            // El authenticator ya intentó refrescar y no pudo: la sesión venció
            e.isUnauthorized() -> expireSession()
            e is AvatarException -> _events.send(ProfileEvent.Message(e.message.orEmpty()))
            else -> _events.send(ProfileEvent.Message(e.userMessage()))
        }
    }

    private fun expireSession() {
        auth.clearSession()
        _state.update { it.copy(sessionExpired = true) }
    }
}
