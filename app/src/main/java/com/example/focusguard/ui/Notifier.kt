package com.example.focusguard

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Shows the gentle "you have been here a while" reminder as a real notification. */
object Notifier {
    private const val CHANNEL_NUDGE = "nudges"
    private const val NUDGE_ID = 1001

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_NUDGE) != null) return
        val channel = NotificationChannel(
            CHANNEL_NUDGE, "Gentle reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "A short message when you have been in one app for a while"
            setSound(null, null)          // appears on screen, but silently
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Returns false if notifications are not allowed, so the caller can use a fallback. */
    @SuppressLint("MissingPermission")
    fun nudge(context: Context, appName: String, minutes: Long, pauseMin: Int): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle("$minutes minutes on $appName")
            .setContentText("A good moment to look up. A short pause comes at $pauseMin minutes.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)       // disappears by itself after 20 seconds
            .setContentIntent(open)
            .build()
        manager.notify(NUDGE_ID, notification)
        return true
    }
}
