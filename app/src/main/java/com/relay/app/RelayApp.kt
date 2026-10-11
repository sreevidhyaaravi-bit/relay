package com.relay.app

import android.app.Application
import android.content.Context

class RelayApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** Access the single [AppContainer] from any Context. */
val Context.relayContainer: AppContainer
    get() = (applicationContext as RelayApp).container
