package com.group41.kotlinapp

import android.app.Application
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.network.ApiClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        Analytics.init(this)
        // Con sesión guardada se usa el id de la última vez (paso 2 del contrato).
        ApiClient.tokens.userId?.let { Analytics.setUserId(it) }
    }
}
