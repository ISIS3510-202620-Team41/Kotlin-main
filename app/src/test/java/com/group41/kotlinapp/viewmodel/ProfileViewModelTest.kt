package com.group41.kotlinapp.viewmodel

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ProfileViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val users = FakeUserRepository()
    private val auth = FakeAuthRepository()
    private val viewModel = ProfileViewModel(users, auth)

    @Test
    fun `carga el usuario una sola vez aunque se pida de nuevo (ej tras rotar)`() {
        viewModel.loadUser()
        viewModel.loadUser()

        assertEquals(testUser, viewModel.state.value.user)
        assertEquals(1, users.meCalls)
    }

    @Test
    fun `force vuelve a pedir el usuario`() {
        viewModel.loadUser()
        viewModel.loadUser(force = true)

        assertEquals(2, users.meCalls)
    }

    @Test
    fun `401 al cargar cierra la sesion`() {
        users.failure = httpError(401)

        viewModel.loadUser()

        assertTrue(viewModel.state.value.sessionExpired)
        assertEquals(1, auth.clearCalls)
    }

    @Test
    fun `sin red no cierra la sesion`() {
        users.failure = IOException("sin red")

        viewModel.loadUser()

        assertFalse(viewModel.state.value.sessionExpired)
        assertFalse(viewModel.state.value.loadingUser)
        assertEquals(0, auth.clearCalls)
    }

    @Test
    fun `guardar con nombre vacio no llama al backend`() {
        viewModel.saveProfile("  ", "hola")

        assertEquals("Escribe tu nombre", viewModel.state.value.nameError)
        assertTrue(users.updates.isEmpty())
    }

    @Test
    fun `descripcion de mas de 300 caracteres no pasa`() {
        viewModel.saveProfile("Ana", "x".repeat(301))

        assertEquals("Máximo 300 caracteres", viewModel.state.value.bioError)
    }

    @Test
    fun `guardar actualiza el usuario y avisa para cerrar el editor`() = runTest {
        viewModel.saveProfile(" Ana María ", " Estudiante ")

        assertEquals("Ana María" to "Estudiante", users.updates.single())
        assertEquals("Ana María", viewModel.state.value.user?.name)
        assertEquals(ProfileEvent.Saved, viewModel.events.first())
    }

    @Test
    fun `subir foto actualiza el usuario y avisa`() = runTest {
        viewModel.uploadAvatar { byteArrayOf(1, 2, 3) }

        val state = viewModel.state.value
        assertEquals("http://localhost:8080/uploads/nueva.jpg", state.user?.avatarUrl)
        assertFalse(state.avatarBusy)
        assertEquals(ProfileEvent.Message("Foto actualizada"), viewModel.events.first())
    }

    @Test
    fun `error al subir muestra el mensaje del backend`() = runTest {
        users.failure = httpError(415, "Formato no permitido. Usa JPG, PNG o WebP")

        viewModel.uploadAvatar { byteArrayOf(1) }

        assertEquals(
            ProfileEvent.Message("Formato no permitido. Usa JPG, PNG o WebP"),
            viewModel.events.first()
        )
        assertFalse(viewModel.state.value.avatarBusy)
    }

    @Test
    fun `quitar foto deja el usuario sin avatar`() {
        users.user = testUser.copy(avatarUrl = "http://localhost:8080/uploads/vieja.jpg")
        viewModel.loadUser()

        viewModel.removeAvatar()

        assertNull(viewModel.state.value.user?.avatarUrl)
    }

    @Test
    fun `URL vacia marca el campo sin llamar al backend`() {
        viewModel.uploadAvatarFromUrl("   ")

        assertEquals("Pega la URL de una imagen", viewModel.state.value.urlError)
        assertNull(viewModel.state.value.user)
    }

    @Test
    fun `abrir el editor descarta avisos viejos`() = runTest {
        // Un guardado que terminó con el editor ya cerrado deja un Saved pendiente
        viewModel.saveProfile("Ana", "")

        viewModel.onEditorOpened()

        assertNull(withTimeoutOrNull(1_000) { viewModel.events.first() })
    }

    @Test
    fun `cerrar sesion llama al backend y marca loggedOut`() {
        viewModel.logout()

        assertEquals(1, auth.logoutCalls)
        assertTrue(viewModel.state.value.loggedOut)
    }
}
