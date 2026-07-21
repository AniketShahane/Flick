package com.flick.sender

import android.app.Application
import com.flick.sender.net.CastCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FlickApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var coordinator: CastCoordinator
        private set
    override fun onCreate() { super.onCreate(); coordinator = CastCoordinator(applicationContext, applicationScope) }
}
