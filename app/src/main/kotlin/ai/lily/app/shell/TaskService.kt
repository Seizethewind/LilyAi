package ai.lily.app.shell

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
 * Keeps the app's process alive while tasks are queued, running or waiting for the person, so they carry on when the app is
 * not on screen. It holds no work itself: the queue lives in the editor activity. The notification says how many tasks need the person.
 */
class TaskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        return START_NOT_STICKY
    }

    /** Android 15 caps a data sync service at about six hours a day and then calls this. The queue is saved, so it picks up at the next start. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val ID = 7401
        private const val CHANNEL = "lily_tasks"

        private fun notification(ctx: Context, needYou: Int): Notification {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Lily tasks", NotificationManager.IMPORTANCE_LOW))
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE) }
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Lily is working")
                .setContentText(if (needYou > 0) "$needYou need you" else "Running your tasks in the background")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** Call from the screen only: Android refuses to start a foreground service from the background. */
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, TaskService::class.java))

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TaskService::class.java))
        }

        /** Changes the number in the notification. Only call while the service is running. */
        fun update(ctx: Context, needYou: Int) {
            ctx.getSystemService(NotificationManager::class.java).notify(ID, notification(ctx, needYou))
        }
    }
}
