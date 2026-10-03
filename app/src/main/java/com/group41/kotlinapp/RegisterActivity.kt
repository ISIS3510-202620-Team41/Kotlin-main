package com.group41.kotlinapp

import android.os.Bundle
import android.util.Patterns
import android.view.View
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
import com.group41.kotlinapp.network.RegisterRequest
import com.group41.kotlinapp.network.userMessage
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

class RegisterActivity : AppCompatActivity() {

    private lateinit var tilName: TextInputLayout
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var tvError: TextView
    private lateinit var btnRegister: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        setContentView(R.layout.activity_register)

        tilName = findViewById(R.id.til_name)
        tilEmail = findViewById(R.id.til_email)
        tilPassword = findViewById(R.id.til_password)
        etName = findViewById(R.id.et_name)
        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        tvError = findViewById(R.id.tv_error)
        btnRegister = findViewById(R.id.btn_register)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnRegister.setOnClickListener { attemptRegister() }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptRegister()
                true
            } else false
        }

        findViewById<MaterialButton>(R.id.btn_google).setOnClickListener {
            Toast.makeText(this, "Disponible pronto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun attemptRegister() {
        tilName.error = null
        tilEmail.error = null
        tilPassword.error = null
        tvError.isVisible = false

        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        // Mismas reglas que el backend, para avisar antes de llamar al servidor
        var valid = true
        if (name.isBlank()) {
            tilName.error = "Escribe tu nombre"
            valid = false
        } else if (name.length > 80) {
            tilName.error = "Máximo 80 caracteres"
            valid = false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = "Escribe un correo válido"
            valid = false
        }
        if (password.length !in 8..72) {
            tilPassword.error = "Debe tener entre 8 y 72 caracteres"
            valid = false
        }
        if (!valid) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.register(RegisterRequest(email, password, name))
                ApiClient.tokens.save(response) // el registro ya deja la sesión iniciada
                openHome()
            } catch (e: HttpException) {
                if (e.code() == 409) {
                    tilEmail.error = "Este correo ya tiene un perfil"
                } else {
                    showError(e.userMessage())
                }
            } catch (e: IOException) {
                showError(e.userMessage())
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnRegister.isEnabled = !loading
        btnRegister.text = if (loading) "Creando perfil..." else "Crear perfil"
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.isVisible = true
    }
}
