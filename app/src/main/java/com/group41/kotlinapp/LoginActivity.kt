package com.group41.kotlinapp

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.LoginRequest
import com.group41.kotlinapp.network.userMessage
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var tvError: TextView
    private lateinit var btnLogin: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ya hay sesión guardada: entrar directo sin mostrar el login
        if (ApiClient.tokens.refresh != null) {
            openHome()
            return
        }

        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        setContentView(R.layout.activity_login)
        requestLocalNetworkIfNeeded()

        tilEmail = findViewById(R.id.til_email)
        tilPassword = findViewById(R.id.til_password)
        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        tvError = findViewById(R.id.tv_error)
        btnLogin = findViewById(R.id.btn_login)

        btnLogin.setOnClickListener { attemptLogin() }

        // "Listo" en el teclado también inicia sesión
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else false
        }

        findViewById<TextView>(R.id.tv_go_register).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_google).setOnClickListener { comingSoon() }
        findViewById<MaterialButton>(R.id.btn_biometric).setOnClickListener { comingSoon() }
    }

    private fun attemptLogin() {
        tilEmail.error = null
        tilPassword.error = null
        tvError.isVisible = false

        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        var valid = true
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = "Escribe un correo válido"
            valid = false
        }
        if (password.isEmpty()) {
            tilPassword.error = "Escribe tu contraseña"
            valid = false
        }
        if (!valid) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.login(LoginRequest(email, password))
                ApiClient.onAuthenticated(response)
                openHome()
            } catch (e: HttpException) {
                showError(
                    if (e.code() == 401) "Correo o contraseña incorrectos" else e.userMessage()
                )
            } catch (e: IOException) {
                showError(e.userMessage())
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        btnLogin.text = if (loading) "Ingresando..." else "Iniciar sesión"
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.isVisible = true
    }

    private fun comingSoon() =
        Toast.makeText(this, "Disponible pronto", Toast.LENGTH_SHORT).show()
}
