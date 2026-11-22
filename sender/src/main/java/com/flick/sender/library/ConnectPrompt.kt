package com.flick.sender.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.flick.sender.MainActivity
import com.flick.sender.R

/**
 * "Connect to <TV>?" (contract §13.5): a heads-up, never a full-screen intent, which Android
 * 14+ keeps for calling and alarm apps. It carries the TV's name and nothing else, so it may
 * show on the lock screen.
 */
internal object ConnectPrompt {
    const val CHANNEL_ID = "tv_connect_requests"

    /** On the body tap's MainActivity Intent: the TV to connect, as the Connect action does. */
    const val EXTRA_OPEN_TV_ID = "com.flick.sender.extra.PROMPT_OPEN_TV_ID"

    /** Beside [EXTRA_OPEN_TV_ID]: proves the Intent came from this prompt (see [tapToken]). */
    const val EXTRA_TAP_TOKEN = "com.flick.sender.extra.PROMPT_TAP_TOKEN"

    /** Excluded from backup and device transfer: a restored token would be another install's. */
    private const val TOKEN_PREFS = "flick_prompt_token"
    private const val TOKEN_KEY = "tap_token"
    private const val TIMEOUT_MS = 60_000L

    /** Idempotent: an existing channel keeps whatever the person set on it. */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.tv_connect_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.tv_connect_channel_desc)
                setShowBadge(false)
            },
        )
    }

    /** Safe off the main thread: the doorbell's listener asks this before answering a knock. */
    fun available(context: Context): Boolean = runCatching {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@runCatching false
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)
        channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    /** False when it could not be shown; the caller then connects without asking. */
    fun post(context: Context, tvId: String, tvName: String?): Boolean {
        createChannel(context)
        if (!available(context)) return false
        val name = tvName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tv_connect_fallback_name)
        val open = PendingIntent.getActivity(
            context,
            ConnectPromptIds.requestCode(tvId, ConnectPromptIds.ACTION_OPEN),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TV_ID, tvId)
                putExtra(EXTRA_TAP_TOKEN, tapToken(context))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.tv_connect_title, name))
            .setContentText(context.getString(R.string.tv_connect_text))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .addAction(0, context.getString(R.string.tv_connect_action_connect), choice(context, tvId, ConnectPromptIds.ACTION_CONNECT))
            .addAction(0, context.getString(R.string.tv_connect_action_later), choice(context, tvId, ConnectPromptIds.ACTION_LATER))
            .build()
        return try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            manager.notify(ConnectPromptIds.notificationId(tvId), notification)
            true
        } catch (e: RuntimeException) {
            // POST_NOTIFICATIONS withdrawn between the check and the post.
            false
        }
    }

    /**
     * This install's body-tap token, created on first use. MainActivity is exported, so any
     * app can send it [EXTRA_OPEN_TV_ID]; only the prompt's PendingIntent carries this.
     */
    @Synchronized
    fun tapToken(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        prefs.getString(TOKEN_KEY, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val token = ConnectPromptTap.newToken()
        prefs.edit().putString(TOKEN_KEY, token).commit()
        return token
    }

    fun cancel(context: Context, tvId: String) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ConnectPromptIds.notificationId(tvId)) }
    }

    private fun choice(context: Context, tvId: String, action: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        ConnectPromptIds.requestCode(tvId, action),
        Intent(context, ConnectPromptReceiver::class.java)
            .setAction(if (action == ConnectPromptIds.ACTION_CONNECT) ConnectPromptReceiver.ACTION_CONNECT else ConnectPromptReceiver.ACTION_LATER)
            .putExtra(ConnectPromptReceiver.EXTRA_TV_ID, tvId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
