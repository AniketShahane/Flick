package com.flick.sender.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flick.sender.FlickApplication

/**
 * The prompt's "Connect" and "Not now". Not exported: only this app's own PendingIntents reach
 * it. The tap is the person acting on the app's UI, which is what lets a resume start from
 * the background here; the coordinator runs it on main, so nothing outlives [onReceive] here.
 */
class ConnectPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tvId = intent.getStringExtra(EXTRA_TV_ID)?.takeIf { it.isNotBlank() } ?: return
        val coordinator = (context.applicationContext as? FlickApplication)?.coordinator
        when (intent.action) {
            ACTION_CONNECT -> coordinator?.onConnectPromptChoice(tvId, connect = true)
                ?: ConnectPrompt.cancel(context, tvId)
            ACTION_LATER -> coordinator?.onConnectPromptChoice(tvId, connect = false)
                ?: ConnectPrompt.cancel(context, tvId)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.flick.sender.action.TV_CONNECT_PROMPT_CONNECT"
        const val ACTION_LATER = "com.flick.sender.action.TV_CONNECT_PROMPT_LATER"
        const val EXTRA_TV_ID = "com.flick.sender.extra.TV_ID"
    }
}
