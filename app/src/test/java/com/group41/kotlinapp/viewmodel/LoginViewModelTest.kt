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

    /**
     * Si el test finge tener conexion. Los tests de abajo asumen que si, porque prueban lo
     * que contesta el backend; el ultimo lo pone en false para probar el caso sin red.
     */
    private var hayConexion = true

    private val viewModel = LoginViewModel(auth, online = { hayConexion })

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

    @Test
    fun `sin conexion no se llama al backend y se explica como entrar sin internet`() {
        hayConexion = false

        viewModel.login("ana@uniandes.edu.co", "contrasena123")

        val state = viewModel.state.value

        // Lo importante es que NO se intento la peticion: sin esta comprobacion el usuario
        // esperaria el tiempo de espera completo de la red para acabar en un error generico.
        assertTrue(auth.logins.isEmpty())
        assertFalse(state.loading)
        assertFalse(state.loggedIn)

        // Y el mensaje tiene que decirle que lo que falta es conexion, no que se equivoco
        // de contrasena, y que si ya habia entrado antes puede entrar sin internet.
        assertTrue(state.error.orEmpty().contains("Sin conexion"))
        assertTrue(state.error.orEmpty().contains("sin internet"))
    }
}
