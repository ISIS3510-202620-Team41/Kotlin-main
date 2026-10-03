package com.group41.kotlinapp

import android.app.Application
import com.group41.kotlinapp.network.ApiClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
    }
}