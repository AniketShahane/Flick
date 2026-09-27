package com.flick.receiver.summon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flick.receiver.MainActivity
import com.flick.receiver.R
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the paired phone's control connection (the Ktor listener in this process)
 * reachable while Flick is in the background, so a cast can open the app.
 *
 * Opt-in ("Open when you cast"), and started only while the app is open: a
 * connectedDevice service cannot be started from the background, and nothing
 * restarts it after the process dies, because a restarted process would have no
 * control server to keep reachable.
 */
class CastReadyService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
        result.onSuccess {
            _running.value = true
        }.onFailure { error ->
            FlickLog.w("summon", "service failed reason=${error.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // The platform raises a foreground-service channel below LOW to LOW anyway.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ready_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager?.createNotificationChannel(channel)

        // CLEAR_TOP|SINGLE_TOP reaches the existing singleTop instance: a second
        // MainActivity would be a second composition and a second control server.
        val open = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.ready_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ready_for_casts"
        private const val NOTIFICATION_ID = 4701

        private val _running = MutableStateFlow(false)

        /** True between a successful startForeground and onDestroy. Main thread. */
        val running: StateFlow<Boolean> = _running

        /** Only from a STARTED Activity: a connectedDevice service cannot start from the background. */
        fun start(context: Context): Boolean = runCatching {
            context.startService(Intent(context, CastReadyService::class.java)) != null
        }.getOrDefault(false)

        /**
         * Clears [running] now rather than at onDestroy, so a stop followed at once
         * by a start (the toggle pressed off then on) still issues the start.
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastReadyService::class.java)) }
            _running.value = false
        }
    }
}
