package com.group41.kotlinapp.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RegisterViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val viewModel = RegisterViewModel(auth)

    @Test
    fun `aplica las mismas reglas que el backend`() {
        viewModel.register("   ", "ana@test", "corta")

        val state = viewModel.state.value
        assertEquals("Escribe tu nombre", state.nameError)
        assertEquals("Escribe un correo válido", state.emailError)
        assertEquals("Debe tener entre 8 y 72 caracteres", state.passwordError)
        assertTrue(auth.registrations.isEmpty())
    }

    @Test
    fun `nombre de mas de 80 caracteres no pasa`() {
        viewModel.register("a".repeat(81), "ana@test.com", "secreta123")

        assertEquals("Máximo 80 caracteres", viewModel.state.value.nameError)
    }

    @Test
    fun `registro correcto marca registered`() {
        viewModel.register(" Ana Pérez ", "ana@test.com", "secreta123")

        assertTrue(viewModel.state.value.registered)
        assertEquals(Triple("Ana Pérez", "ana@test.com", "secreta123"), auth.registrations.single())
    }

    @Test
    fun `correo ya registrado marca el campo de correo`() {
        auth.failure = httpError(409, "Ese correo ya esta registrado")

        viewModel.register("Ana", "ana@test.com", "secreta123")

        val state = viewModel.state.value
        assertEquals("Este correo ya tiene un perfil", state.emailError)
        assertNull(state.error)
        assertFalse(state.registered)
        assertFalse(state.loading)
    }
}
