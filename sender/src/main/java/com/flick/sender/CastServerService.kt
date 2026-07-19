package com.flick.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [MediaHttpServer] for the life of a cast
 * session. Running the server here (with an ongoing notification and the
 * mediaPlayback foreground type) keeps serving alive while the TV plays and
 * while the phone's screen is off.
 */
class CastServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var httpServer: MediaHttpServer

    override fun onCreate() {
        super.onCreate()
        httpServer = MediaHttpServer(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything(setIdle = true)
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val uri = intent.data
                val name = intent.getStringExtra(EXTRA_NAME)
                val size = intent.getLongExtra(EXTRA_SIZE, -1L)

                // A foreground service MUST post its notification promptly, so do
                // it synchronously before any I/O.
                startInForeground(buildNotification(text = getString(R.string.notif_text_serving)))

                if (uri == null) {
                    ServerStateHolder.setError(getString(R.string.error_server_start))
                    stopEverything(setIdle = false)
                    return START_NOT_STICKY
                }

                serviceScope.launch {
                    val ip = NetworkUtils.getSiteLocalIpv4()
                    if (ip == null) {
                        ServerStateHolder.setError(getString(R.string.error_no_lan))
                        stopEverything(setIdle = false)
                        return@launch
                    }
                    try {
                        httpServer.start(uri)
                        ServerStateHolder.setRunning(name, size, ip)
                        updateNotification(text = "http://$ip:$SERVER_PORT/video")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start media server", e)
                        ServerStateHolder.setError(getString(R.string.error_server_start))
                        stopEverything(setIdle = false)
                    }
                }
                return START_NOT_STICKY
            }

            else -> {
                // Unknown/relaunch intent: nothing to serve, so shut down cleanly.
                stopEverything(setIdle = true)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        // Belt-and-braces: make sure the socket is released and coroutines die.
        httpServer.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopEverything(setIdle: Boolean) {
        httpServer.stop()
        // Don't wipe an error surfaced by someone else (e.g. the Activity stopping
        // a stale server after losing its LAN IP, then setting ERROR) back to
        // IDLE — the async ACTION_STOP that tears down that server would otherwise
        // clobber the error the user needs to see.
        if (setIdle && ServerStateHolder.state.value.status != ServerStatus.ERROR) {
            ServerStateHolder.setIdle()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Notification -------------------------------------------------------

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, REQ_OPEN, openIntent, pendingFlags(),
        )

        val stopIntent = Intent(this, CastServerService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, REQ_STOP, stopIntent, pendingFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_action_stop),
                stopPending,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CastServerService"

        private const val CHANNEL_ID = "cast_server"
        private const val NOTIF_ID = 42
        private const val REQ_OPEN = 1
        private const val REQ_STOP = 2

        const val ACTION_START = "com.flick.sender.action.START"
        const val ACTION_STOP = "com.flick.sender.action.STOP"
        private const val EXTRA_NAME = "com.flick.sender.extra.NAME"
        private const val EXTRA_SIZE = "com.flick.sender.extra.SIZE"

        private fun pendingFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            return flags
        }

        /** Start (or re-target) the foreground media server for [uri]. */
        fun start(context: Context, uri: Uri, name: String?, size: Long) {
            val intent = Intent(context, CastServerService::class.java).apply {
                action = ACTION_START
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SIZE, size)
            }
            startForegroundServiceCompat(context, intent)
        }

        /** Stop the media server and dismiss the foreground notification. */
        fun stop(context: Context) {
            val intent = Intent(context, CastServerService::class.java).setAction(ACTION_STOP)
            // Delivered as a normal start command; the service tears itself down.
            context.startService(intent)
        }

        private fun startForegroundServiceCompat(context: Context, intent: Intent) {
            // minSdk is 26 (O), so startForegroundService is always available.
            context.startForegroundService(intent)
        }
    }
}
