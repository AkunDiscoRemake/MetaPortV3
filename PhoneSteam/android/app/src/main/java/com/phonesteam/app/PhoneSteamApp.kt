package com.phonesteam.app

import android.app.Application
import com.phonesteam.app.input.JoyConManager
import com.phonesteam.app.util.Settings

class PhoneSteamApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var joyCons: JoyConManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        joyCons = JoyConManager(this)
    }
}
