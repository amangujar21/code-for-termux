package dev.amang.claudetermux.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.amang.claudetermux.App
import dev.amang.claudetermux.MainActivity
import dev.amang.claudetermux.R
import dev.amang.claudetermux.chat.RunState

private const val CHANNEL_WORKING = "working"
private const val CHANNEL_ALERTS = "alerts"
private const val WORKING_ID = 1

/**
 * Keeps the process (and so the bridge socket) alive while Claude is mid-turn, and tells the user
 * when a turn finishes or needs a permission decision while the app is not on screen.
 */
class TurnTracker(private val app: App) {
    private val states = HashMap<String, RunState>()

    init {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WORKING, "Working", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Finished and needs attention", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun onRunState(sessionId: String, title: String, state: RunState) {
        val previous = states[sessionId] ?: RunState.Idle
        if (previous == state) return
        if (state == RunState.Idle) states.remove(sessionId) else states[sessionId] = state

        if (!app.inForeground) {
            when {
                state == RunState.WaitingPermission -> alert(sessionId, title, "Needs your permission to continue")
                state == RunState.Idle && previous != RunState.Idle -> alert(sessionId, title, "Finished")
            }
        }

        val anyRunning = states.values.any { it == RunState.Running }
        val intent = Intent(app, TurnService::class.java)
        if (anyRunning) {
            // Starting a foreground service from the background is not allowed; the turn was
            // started while visible, so this only matters for transitions that happen later.
            runCatching { ContextCompat.startForegroundService(app, intent) }
        } else {
            app.stopService(intent)
        }
    }

    fun forget(sessionId: String) = onRunState(sessionId, "", RunState.Idle)

    fun clearNotifications() {
        val manager = NotificationManagerCompat.from(app)
        manager.activeNotifications.filter { it.id != WORKING_ID }.forEach { manager.cancel(it.id) }
    }

    private fun alert(sessionId: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= 33
        ) return
        val notification = NotificationCompat.Builder(app, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_prompt)
            .setContentTitle(title.ifBlank { "Claude Code" })
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(app, sessionId))
            .build()
        NotificationManagerCompat.from(app).notify(sessionId.hashCode(), notification)
    }
}

private fun openApp(context: Context, sessionId: String?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
    return PendingIntent.getActivity(
        context,
        sessionId?.hashCode() ?: 0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class TurnService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_stat_prompt)
            .setContentTitle("Claude is working")
            .setOngoing(true)
            .setContentIntent(openApp(this, null))
            .build()
        ServiceCompat.startForeground(
            this,
            WORKING_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        return START_NOT_STICKY
    }
}
