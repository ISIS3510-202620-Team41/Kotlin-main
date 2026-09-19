package com.group41.kotlinapp

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class CreateActivityStep1Activity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        setContentView(R.layout.activity_create_activity_step1)

        val root = findViewById<View>(R.id.main)
        val buttonsContainer = findViewById<View>(R.id.ll_buttons)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            buttonsContainer.setPadding(
                buttonsContainer.paddingLeft,
                buttonsContainer.paddingTop,
                buttonsContainer.paddingRight,
                systemBars.bottom
            )
            insets
        }

        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }
}