package com.group41.kotlinapp

import android.os.Bundle
import android.view.View
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
import com.group41.kotlinapp.viewmodel.RegisterUiState
import com.group41.kotlinapp.viewmodel.RegisterViewModel
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private val viewModel: RegisterViewModel by viewModels()

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
        btnRegister.setOnClickListener { submit() }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }

        findViewById<MaterialButton>(R.id.btn_google).setOnClickListener {
            Toast.makeText(this, "Disponible pronto", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun submit() {
        viewModel.register(
            etName.text.toString(),
            etEmail.text.toString(),
            etPassword.text.toString()
        )
    }

    private fun render(state: RegisterUiState) {
        if (state.registered) {
            openHome()
            return
        }
        tilName.error = state.nameError
        tilEmail.error = state.emailError
        tilPassword.error = state.passwordError
        tvError.text = state.error
        tvError.isVisible = state.error != null
        btnRegister.isEnabled = !state.loading
        btnRegister.text = if (state.loading) "Creando perfil..." else "Crear perfil"
    }
}
