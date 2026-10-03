package com.group41.kotlinapp

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.group41.kotlinapp.viewmodel.LoginUiState
import com.group41.kotlinapp.viewmodel.LoginViewModel
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private val viewModel: LoginViewModel by viewModels()

    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var tvError: TextView
    private lateinit var btnLogin: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ya hay sesión guardada: entrar directo sin mostrar el login
        if (viewModel.hasSession()) {
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

        btnLogin.setOnClickListener { submit() }

        // "Listo" en el teclado también inicia sesión
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }

        findViewById<TextView>(R.id.tv_go_register).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_google).setOnClickListener { comingSoon() }
        findViewById<MaterialButton>(R.id.btn_biometric).setOnClickListener { comingSoon() }

        // Se redibuja cada vez que cambia el estado; solo mientras la pantalla está visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun submit() {
        viewModel.login(etEmail.text.toString(), etPassword.text.toString())
    }

    private fun render(state: LoginUiState) {
        if (state.loggedIn) {
            openHome()
            return
        }
        tilEmail.error = state.emailError
        tilPassword.error = state.passwordError
        tvError.text = state.error
        tvError.isVisible = state.error != null
        btnLogin.isEnabled = !state.loading
        btnLogin.text = if (state.loading) "Ingresando..." else "Iniciar sesión"
    }

    private fun comingSoon() =
        Toast.makeText(this, "Disponible pronto", Toast.LENGTH_SHORT).show()
}
