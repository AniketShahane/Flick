package com.flick.sender

import android.app.Application
import com.flick.sender.media.releaseRetiredSubtitleFolder
import com.flick.sender.net.CastCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    override fun onCreate() {
        super.onCreate()
        coordinator = CastCoordinator(applicationContext, applicationScope)
        // Here rather than on the one screen that knows about it: the grant a retired
        // subtitles source took is invisible from every surface, so no surface can be the
        // thing that expires it. This scope is Main.immediate, so the call runs inline only
        // as far as its own `withContext(Dispatchers.IO)` — nothing it does is on the path
        // to the first frame, and nothing waits for it.
        applicationScope.launch { releaseRetiredSubtitleFolder(applicationContext) }
    }
}
