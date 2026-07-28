package com.flick.sender

import android.app.Application
import com.flick.sender.net.CastCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FlickApplication : Application() {
    /**
     * Deliberately carries no CoroutineExceptionHandler. The library load, the
     * subtitle job, frame collection and every pairing job run here, so a handler at
     * this level would decide the fate of all of them at once and hide genuine
     * defects behind a UI that reports something milder. Containment belongs to the
     * coroutine that can classify its own failure — see ControlClient's transport
     * handler, which is why it is attached at the `launch` and not here.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var coordinator: CastCoordinator
        private set
    override fun onCreate() { super.onCreate(); coordinator = CastCoordinator(applicationContext, applicationScope) }
}
