package ai.lily.app.terminal

import ai.lily.app.terminal.ui.TerminalHost
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
import androidx.core.content.ContextCompat

/**
 * Keeps the app's process alive while a terminal shell is running, so a build or a server started in the terminal carries
 * on when the app is not on screen. It holds no shell itself: the sessions live in [TerminalHost], which belongs to the
 * process. The notification has a Stop button that ends every shell.
 *
 * Android can still stop the app (low memory, "force stop"); then the shells end with it.
 */
class TerminalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TerminalHost.get(applicationContext).closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val running = intent?.getIntExtra(EXTRA_RUNNING, 1) ?: 1
        val n = notification(this, running)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        // If Android restarts the app, the shells are gone, so there is nothing to bring back.
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 7402
        private const val CHANNEL = "lily_terminal"
        private const val ACTION_STOP = "ai.lily.app.terminal.STOP"
        private const val EXTRA_RUNNING = "running"

        private fun notification(ctx: Context, running: Int): Notification {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Terminal", NotificationManager.IMPORTANCE_LOW))
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE) }
            val stop = PendingIntent.getService(
                ctx, 1, Intent(ctx, TerminalService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Terminal")
                .setContentText(TerminalNotice.text(running))
                .setContentIntent(open)
                .addAction(0, "Stop all", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** Call only from something the person just did on screen: Android refuses to start a foreground service from the background. */
        fun start(ctx: Context, running: Int) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, TerminalService::class.java).putExtra(EXTRA_RUNNING, running))

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TerminalService::class.java))
        }

        /** Changes the count in the notification. Safe from the background; only call while shells are running. */
        fun update(ctx: Context, running: Int) {
            ctx.getSystemService(NotificationManager::class.java).notify(ID, notification(ctx, running))
        }
    }
}
