package com.flick.sender.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flick.sender.MainActivity
import com.flick.sender.R
import com.flick.sender.util.FlickLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Contract §8: the ready window. A `connectedDevice` foreground service whose only job is to
 * keep this process — and with it the library socket — out of the cached state Android 14+
 * freezes ten seconds in, so the TV can still browse and pick right after a film.
 *
 * It holds no wake lock and no Wi-Fi lock: the screen-off phone keeps its socket on the
 * radio's own terms, and [TvLibrary] closes the window on its timers.
 */
class TvReadyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                createChannel()
                val device = intent.getStringExtra(EXTRA_TV)?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.notif_device_generic)
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIF_ID,
                        notification(device),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                } catch (e: RuntimeException) {
                    // A start the platform let through and then refused at promotion: the window
                    // simply does not open.
                    FlickLog.w("library", "ready window refused err=${e.javaClass.simpleName}")
                    events.tryEmit(Unit)
                    stopSelfResult(startId)
                }
            }
            ACTION_USER_STOP -> {
                events.tryEmit(Unit)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            else -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(device: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags(),
        )
        val stop = PendingIntent.getService(
            this,
            REQ_STOP,
            Intent(this, TvReadyService::class.java).setAction(ACTION_USER_STOP),
            pendingFlags(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.tv_ready_title, device))
            .setContentText(getString(R.string.tv_ready_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                androidx.media3.session.R.drawable.media3_icon_stop,
                getString(R.string.notif_action_stop),
                stop,
            )
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tv_ready_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tv_ready_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tv_ready"
        private const val NOTIF_ID = 43
        private const val REQ_OPEN = 11
        private const val REQ_STOP = 12
        private const val ACTION_START = "com.flick.sender.action.TV_READY_START"
        private const val ACTION_STOP = "com.flick.sender.action.TV_READY_STOP"
        private const val ACTION_USER_STOP = "com.flick.sender.action.TV_READY_USER_STOP"
        private const val EXTRA_TV = "com.flick.sender.extra.TV_NAME"

        private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

        /** The window ended from inside the service: the person pressed Stop, or promotion was refused. */
        internal val ended: SharedFlow<Unit> = events.asSharedFlow()

        /** False when the platform refused the start; the window then does not open. */
        internal fun start(context: Context, tvName: String?): Boolean = try {
            context.startForegroundService(
                Intent(context, TvReadyService::class.java).setAction(ACTION_START).putExtra(EXTRA_TV, tvName),
            )
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on API 31+, an IllegalStateException.
            FlickLog.w("library", "ready window start refused err=${e.javaClass.simpleName}")
            false
        }

        /**
         * Delivered as a start command rather than `stopService`, so it lands after the start
         * command that promoted the service and never ends one before `startForeground`.
         */
        internal fun stop(context: Context) {
            try {
                context.startService(Intent(context, TvReadyService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                // Refused only while this app holds no foreground service, so nothing is running.
                FlickLog.d("library", "ready window stop refused err=${e.javaClass.simpleName}")
            }
        }

        private fun pendingFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_IMMUTABLE
            return flags
        }
    }
}
