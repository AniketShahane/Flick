package com.flick.sender.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flick.sender.FlickApplication

/**
 * Contract §8: after a reboot or an update nothing else would bring ready mode back until
 * Flick is next opened. Starts the service only when ready mode holds.
 */
class TvReadyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                (context.applicationContext as? FlickApplication)?.coordinator?.onSystemStart()
        }
    }
}
