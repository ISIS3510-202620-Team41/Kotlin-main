package com.group41.kotlinapp.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class LoginViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val viewModel = LoginViewModel(auth)

    @Test
    fun `datos invalidos muestran errores y no llaman al backend`() {
        viewModel.login("no-es-correo", "")

        val state = viewModel.state.value
        assertEquals("Escribe un correo válido", state.emailError)
        assertEquals("Escribe tu contraseña", state.passwordError)
        assertTrue(auth.logins.isEmpty())
    }

    @Test
    fun `login correcto marca loggedIn y limpia espacios del correo`() {
        viewModel.login("  ana@test.com ", "secreta123")

        val state = viewModel.state.value
        assertTrue(state.loggedIn)
        assertFalse(state.loading)
        assertEquals("ana@test.com" to "secreta123", auth.logins.single())
    }

    @Test
    fun `401 muestra credenciales incorrectas`() {
        auth.failure = httpError(401, "Credenciales invalidas")

        viewModel.login("ana@test.com", "mala")

        val state = viewModel.state.value
        assertEquals("Correo o contraseña incorrectos", state.error)
        assertFalse(state.loggedIn)
        assertFalse(state.loading)
    }

    @Test
    fun `429 muestra el mensaje del backend`() {
        auth.failure = httpError(429, "Demasiados intentos fallidos. Espera unos minutos")

        viewModel.login("ana@test.com", "mala")

        assertEquals("Demasiados intentos fallidos. Espera unos minutos", viewModel.state.value.error)
    }

    @Test
    fun `sin red avisa que no se pudo conectar`() {
        auth.failure = IOException("timeout")

        viewModel.login("ana@test.com", "secreta123")

        assertEquals("No se pudo conectar con el servidor", viewModel.state.value.error)
    }

    @Test
    fun `un nuevo intento borra el error anterior`() {
        auth.failure = httpError(401)
        viewModel.login("ana@test.com", "mala")
        auth.failure = null

        viewModel.login("ana@test.com", "buena123")

        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.loggedIn)
    }
}
